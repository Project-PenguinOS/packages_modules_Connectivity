/*
 * Copyright (C) 2025 Samsung Electronics.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Authors: Jayendra Reddy Kovvuri, Madhan Raj Kanagarathinam, Sandeep Irlanki
 *
 * Description: eBPF-based implementation of AccECN for IPv4 and IPv6 TCP connections.
 *              Includes separate handling for Ethernet and raw IP packets.
 */

DEFINE_BPF_MAP_NO_NETD(l4s_conn_counter, ARRAY, uint32_t, uint32_t, 1)
DEFINE_BPF_MAP_NO_NETD(l4s_accecn_enabled_map, ARRAY, uint32_t, bool, 1)

function bool is_l4s_enabled() {
    uint32_t zero = 0;
    bool *enabled = bpf_l4s_accecn_enabled_map_lookup_elem(&zero);
    return enabled && *enabled;
}

typedef struct {
    __u8 u8[3];
} be24;
STRUCT_SIZE(be24, 3);

function void assign_be24(be24 * const v, unsigned x) {
    v->u8[2] = x; x >>= 8;
    v->u8[1] = x; x >>= 8;
    v->u8[0] = x;
}

typedef struct {
    __u8 kind;
    __u8 length;
    be24 e1b, ceb, e0b;
} tcp_accecn_option;
STRUCT_SIZE(tcp_accecn_option, 1 + 1 + 3 * 3); // 11

// prefer long/u64 because they're native register size.
// int/u32 require constant <<=32 >>=32 adjustments
procedure long find_accecn_options_offset(struct __sk_buff *skb, uint64_t offset) {
    struct tcphdr tcp_header;
    if (bpf_skb_load_bytes(skb, offset, &tcp_header, sizeof(tcp_header))) return -1;

    // nibble, so 0..15, counts u32s, so 0..60, but 20 tcp header + 0..40 options
    if (tcp_header.doff < 5) return -1;  // invalid TCP header

    const uint64_t end_off = offset + tcp_header.doff * 4;
    uint64_t opt_off = offset + sizeof(tcp_header);

    // in theory could have 40-11 NOPs, then 11 byte accecn option, thus 8 should be 40-11 + 1
    for (uint64_t i = 0; i < 8; i++) {
        // is there still room for a true option?
        if (opt_off + 2 > end_off) break;

        // in case of EOL/NOP we'll read garbage length, but it doesn't hurt us
        struct {
          uint8_t kind, length;
        } option;
        if (bpf_skb_load_bytes(skb, opt_off, &option, sizeof(option))) return -1;

        // TCP option 'End of Option List' - no length field, done.
        if (option.kind == 0) break;

        // TCP option 'No-Operation' - no length field (used for padding)
        if (option.kind == 1) {
            opt_off++;
            continue;
        }

        // all other TCP options have a length field, and it MUST be >= 2
        if (option.length < 2) break;

        // does the TCP option fit in the TCP header?
        if (opt_off + option.length > end_off) break;

        // TCP options 'Accurate ECN Order 0/1 (AccECN0/1)'
        if (option.kind == 172 || option.kind == 174)
            return opt_off - offset;

        // Move on to the next option
        opt_off += option.length;
    }

    // TCP AccECN option not found
    return -1;
}

procedure int update_accecn_counter(struct __sk_buff* skb) {
    if (!is_l4s_enabled()) return 1;
    struct bpf_sock *sk = skb->sk;
    if (!sk) return 1;
    SkStorageValue *sks = bpf_sk_storage_get(sk, 0, 0);
    if (!sks || sks->l4s.disabled) return 1;

    void* data = (void*)(long)skb->data;
    void* data_end = (void*)(long)skb->data_end;

    if (data + sizeof(struct iphdr) > data_end) {
        return 1;
    }

    const bool isIpv4 = skb->protocol == htons(ETH_P_IP);
    const bool isIpv6 = skb->protocol == htons(ETH_P_IPV6);
    struct tcphdr* tcph = NULL;
    __u8 ip_ecn = 0;
    uint64_t payload_size = 0;
    int hdr_len = 0;

    if (isIpv4) {
        struct iphdr* ip = data;
        if (ip->protocol == IPPROTO_TCP) {
            if (data + sizeof(struct iphdr) + sizeof(struct tcphdr) > data_end) {
                return 1;
            }
            tcph = (void*)(ip + 1);
            ip_ecn = ip->tos & 0x03;
            payload_size = ntohs(ip->tot_len) - (ip->ihl * 4) - (tcph->doff * 4);
            hdr_len += sizeof(struct iphdr);
        } else {
            return 1;
        }
    } else if (isIpv6) {
        if (data + sizeof(struct ipv6hdr) > data_end) {
            return 1;
        }
        struct ipv6hdr* ip6 = data;
        if (ip6->nexthdr == IPPROTO_TCP) {
            if (data + sizeof(struct ipv6hdr) + sizeof(struct tcphdr) > data_end) {
                return 1;
            }
            tcph = (void*)(ip6 + 1);
            ip_ecn = (ip6->flow_lbl[0] & 0x30) >> 4;
            payload_size = ntohs(ip6->payload_len) - (tcph->doff * 4);
            hdr_len += sizeof(struct ipv6hdr);
        } else {
            return 1;
        }
    } else {
        return 1;
    }

    if (!isIpv4 && !isIpv6) {
        return 1;
    }

    int tcp_flags_offset = isIpv4 ? IP4_TCP_OFFSET(flags16) : IP6_TCP_OFFSET(flags16);

    if (tcph->syn && tcph->ack) {
        __u16 flags;
        if (bpf_skb_load_bytes_relative(skb, tcp_flags_offset, &flags,
                                        sizeof(flags), BPF_HDR_START_NET)) {
            return 1;
        }
        __u16 ace = (ntohs(flags) & 0x01c0) >> 6;

        if (ace == 0b010 || ace == 0b011 || ace == 0b100 || ace == 0b110) {
            sks->l4s.ce_count = (ip_ecn == 0b11) ? 0b110 : 0b101;
            sks->l4s.ce_inited = true;

            uint32_t conn_key = 0;
            uint32_t* conn_count = bpf_l4s_conn_counter_lookup_elem(&conn_key);
            uint32_t oneConnection = 1;
            if (!conn_count) {
                bpf_l4s_conn_counter_update_elem(&conn_key, &oneConnection, 0);
            } else {
                __sync_fetch_and_add(conn_count, oneConnection);
            }

            if (!sks->l4s.byte_inited) {
                int is_accecn = find_accecn_options_offset(skb, hdr_len);
                if (is_accecn != -1) {
                    sks->l4s.byte_inited = true;
                    sks->l4s.e0b = 1;
                    sks->l4s.e1b = 1;
                    sks->l4s.ceb = 0;
                }
            }

            return 1;
        }

        return 1;
    }

    if (tcph->fin || tcph->rst) return 1;

    if (ip_ecn == 0b11) {
        __u32 ce_packets = skb->gso_segs;
        __sync_fetch_and_add(&sks->l4s.ce_count, ce_packets);
    }

    if (sks->l4s.byte_inited) {
        if (ip_ecn == 0b11) {
            __sync_fetch_and_add(&sks->l4s.ceb, payload_size);
        }
        else if (ip_ecn == 0b10) {
           __sync_fetch_and_add(&sks->l4s.e0b, payload_size);
        }
        else if (ip_ecn == 0b01) {
           __sync_fetch_and_add(&sks->l4s.e1b, payload_size);
        }
    }
    return 1;
}

DEFINE_BPF_PROG_KVER_RANGE(sockops, accecn_option, AID_SYSTEM, 6_1, 6_18)
(struct bpf_sock_ops *skops) {
    if (!is_l4s_enabled()) return 1;
    struct bpf_sock *sk = skops->sk;
    if (!sk) return 1;
    SkStorageValue *sks = bpf_sk_storage_get(sk, 0, 0);
    if (!sks || sks->l4s.disabled) return 1;
    switch (skops->op) {
        case BPF_SOCK_OPS_TCP_CONNECT_CB:
        case BPF_SOCK_OPS_PASSIVE_ESTABLISHED_CB:
             bpf_sock_ops_cb_flags_set(
                skops,
                skops->bpf_sock_ops_cb_flags |
                BPF_SOCK_OPS_WRITE_HDR_OPT_CB_FLAG
            );
            break;
        case BPF_SOCK_OPS_HDR_OPT_LEN_CB:
        {
            if (skops->skb_tcp_flags & TCP_FLAG8_SYN) break;
            if (!sks->l4s.byte_inited) break;
            bpf_reserve_hdr_opt(skops, sizeof(tcp_accecn_option), 0);
            break;
        }
        case BPF_SOCK_OPS_WRITE_HDR_OPT_CB:
        {
            if (skops->skb_tcp_flags & TCP_FLAG8_SYN) break;
            if (!sks->l4s.byte_inited) break;

            tcp_accecn_option opt = {
                .kind = 174,
                .length = sizeof(opt),
            };

            assign_be24(&opt.e1b, sks->l4s.e1b);
            assign_be24(&opt.ceb, sks->l4s.ceb);
            assign_be24(&opt.e0b, sks->l4s.e0b);

            bpf_store_hdr_opt(skops, &opt, sizeof(opt), 0);
            break;
        }
        default:
            break;
    }
    return 1;
}

function void do_egress_accecn(struct __sk_buff* skb, const struct rawip_bool rawip) {
    const bool is_ethernet = !rawip.rawip;
    const int l2_header_size = is_ethernet ? sizeof(struct ethhdr) : 0;

    if (!is_l4s_enabled()) return;
    struct bpf_sock *sk = skb->sk;
    if (!sk) return;
    SkStorageValue *sks = bpf_sk_storage_get(sk, 0, 0);
    if (!sks || sks->l4s.disabled) return;

    void* data = (void*)(long)skb->data;
    void* data_end = (void*)(long)skb->data_end;

    if (data + l2_header_size + sizeof(struct iphdr) > data_end) return;

    const bool isIpv4 = skb->protocol == htons(ETH_P_IP);
    const bool isIpv6 = skb->protocol == htons(ETH_P_IPV6);
    struct tcphdr* tcph;

    if (isIpv4) {
        if (data + l2_header_size + sizeof(struct iphdr) + sizeof(struct tcphdr) > data_end)
            return;

        struct iphdr* ip = data + l2_header_size;
        if (ip->protocol != IPPROTO_TCP) return;

        tcph = (struct tcphdr*)(ip + 1);
    } else if (isIpv6) {
        if (data + l2_header_size + sizeof(struct ipv6hdr) + sizeof(struct tcphdr) > data_end)
            return;

        struct ipv6hdr* ip6 = data + l2_header_size;
        if (ip6->nexthdr != IPPROTO_TCP) return;

        tcph = (struct tcphdr*)(ip6 + 1);
    } else return;

    int tcp_flags_offset = l2_header_size + (isIpv4 ? IP4_TCP_OFFSET(flags16) : IP6_TCP_OFFSET(flags16));
    int tcp_csum_offset = l2_header_size + (isIpv4 ? IP4_TCP_OFFSET(check) : IP6_TCP_OFFSET(check));

    // if SYN, then set ACE to 111
    if (tcph->syn && !tcph->ack) {
        __u16 cur_flags = load_half(skb, tcp_flags_offset);
        __u16 new_flags = htons(cur_flags | 0x01c0);
        __u16 cur_ace = (cur_flags & 0x01c0) >> 6;

        // connection requesting AccECN by default
        if (cur_ace == 0b111) return;

        if (bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2))
            return;
        if (bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0))
            return;
        return;
    }

    if (!sks->l4s.ce_inited) return;

    __u16 cur_flags = load_half(skb, tcp_flags_offset);
    __u16 new_flags = htons((cur_flags & 0xfe3f) | ((sks->l4s.ce_count & 7) << 6));

    bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
    bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);

    int ip_tos_offset = l2_header_size + (isIpv4 ? IP4_OFFSET(tos) : IP6_OFFSET(flow_lbl));
    __u8 old_tos = load_byte(skb, ip_tos_offset);
    __u8 new_tos = old_tos | (isIpv4 ? 0x01 : 0x10);

    if (isIpv4) {
        bpf_l3_csum_replace(skb, l2_header_size + IP4_OFFSET(check), htons(old_tos), htons(new_tos), 2);
    }
    bpf_skb_store_bytes(skb, ip_tos_offset, &new_tos, sizeof(new_tos), 0);
}

DEFINE_BPF_PROG_KVER_RANGE(schedcls, egress_accecn_eth, AID_SYSTEM, 6_1, 6_18)
(struct __sk_buff* skb) {
    do_egress_accecn(skb, ETHER);
    return TC_ACT_PIPE;
}

DEFINE_BPF_PROG_KVER_RANGE(schedcls, egress_accecn_rawip, AID_SYSTEM, 6_1, 6_18)
(struct __sk_buff* skb) {
    do_egress_accecn(skb, RAWIP);
    return TC_ACT_PIPE;
}
