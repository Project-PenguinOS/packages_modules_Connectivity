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

function int find_accecn_options_offset(struct __sk_buff *skb, uint8_t offset) {
    int ret;
    uint8_t opt_off;
    struct tcphdr tcp_header = {0};

    ret = bpf_skb_load_bytes(skb, offset, &tcp_header, (sizeof(struct tcphdr)));
    if (ret) {
        return -1;
    }
    opt_off = offset + (sizeof(struct tcphdr));
    for (int i = 0; i < 8; i++) {
        uint8_t kind;
        uint8_t length;

        ret = bpf_skb_load_bytes(skb, opt_off, &kind, 1);
        if (ret) {
            return -1;
        }
        if (kind == 172 || kind == 174) {
            return opt_off-offset;
        }
        if (kind == 0) {
            break;
        } else if (kind == 1) {
            opt_off += 1;
        } else {
            ret = bpf_skb_load_bytes(skb, opt_off + 1, &length, 1);
            if (ret || length < 2) {
                return -1;
            }
            opt_off += length;
        }
    }
    return -1;
}

function int parse_tcp_mss_option(struct __sk_buff *skb, uint8_t offset) {
    int ret;
    uint8_t opt_off;
    struct tcphdr tcp_header = {0};

    ret = bpf_skb_load_bytes(skb, offset, &tcp_header, sizeof(struct tcphdr));
    if (ret)
        return -1;

    opt_off = offset + sizeof(struct tcphdr);

    for (int i = 0; i < 8; i++) {
        uint8_t kind = 0, length = 0;
        ret = bpf_skb_load_bytes(skb, opt_off, &kind, 1);
        if (ret)
            return -1;
        if (kind == 0)
            break;
        if (kind == 1) {
            opt_off += 1;
            continue;
        }

        ret = bpf_skb_load_bytes(skb, opt_off + 1, &length, 1);
        if (ret || length < 2)
            return -1;
        if (kind == 2 && length == 4) {
            uint16_t mss = 0;
            ret = bpf_skb_load_bytes(skb, opt_off + 2, &mss, 2);
            if (ret)
                return -1;
            return ntohs(mss);
        }
        opt_off += length;
    }
    return -1;
}

function int is_l4s_enabled() {
    uint32_t status_key = 0;
    bool *l4s_status_ptr = bpf_l4s_accecn_enabled_map_lookup_elem(&status_key);
    return l4s_status_ptr && *l4s_status_ptr;
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

DEFINE_BPF_PROG_KVER_RANGE(sockops, accecn_option, AID_SYSTEM, 6_1, 6_18)
(struct bpf_sock_ops *skops) {
    if (!is_l4s_enabled()) return 1;
    if (!skops->sk) {
        return 1;
    }
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

            SkStorageValue* sks = bpf_sk_storage_get(skops->sk, 0, 0);
            if (!sks || !sks->l4s.byte_inited) break;
            bpf_reserve_hdr_opt(skops, sizeof(tcp_accecn_option), 0);
            break;
        }
        case BPF_SOCK_OPS_WRITE_HDR_OPT_CB:
        {
            if (skops->skb_tcp_flags & TCP_FLAG8_SYN) break;

            SkStorageValue* sks = bpf_sk_storage_get(skops->sk, 0, 0);
            if (!sks || !sks->l4s.byte_inited) break;

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

DEFINE_BPF_PROG_KVER_RANGE(schedcls, egress_accecn_eth, AID_SYSTEM, 6_1, 6_18)
(struct __sk_buff* skb) {
    if (!is_l4s_enabled()) return TC_ACT_PIPE;

    void* data = (void*)(long)skb->data;
    void* data_end = (void*)(long)skb->data_end;

    if (data + sizeof(struct ethhdr) + sizeof(struct iphdr) > data_end) {
        return TC_ACT_PIPE;
    }

    const bool isIpv4 = skb->protocol == htons(ETH_P_IP);
    const bool isIpv6 = skb->protocol == htons(ETH_P_IPV6);
    struct tcphdr* tcph = NULL;

    if (isIpv4) {
        struct iphdr* ip = data + ETH_HLEN;
        if (ip->protocol == IPPROTO_TCP) {
            if (data + sizeof(struct ethhdr) + sizeof(struct iphdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip + 1);
        } else {
            return TC_ACT_PIPE;
        }
    } else if (isIpv6) {
        if (data + sizeof(struct ethhdr) + sizeof(struct ipv6hdr) > data_end) {
            return TC_ACT_PIPE;
        }
        struct ipv6hdr* ip6 = data + ETH_HLEN;
        if (ip6->nexthdr == IPPROTO_TCP) {
            if (data + sizeof(struct ethhdr) + sizeof(struct ipv6hdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip6 + 1);
        } else {
            return TC_ACT_PIPE;
        }
    } else {
        return TC_ACT_PIPE;
    }

    int tcp_flags_offset = isIpv4 ? ETH_IP4_TCP_OFFSET(flags16) : ETH_IP6_TCP_OFFSET(flags16);
    int tcp_csum_offset = isIpv4 ? ETH_IP4_TCP_OFFSET(check) : ETH_IP6_TCP_OFFSET(check);
    int ret = 0;

    // if SYN, then set ACE to 111
    if (tcph->syn && !tcph->ack) {
        __u16 cur_flags = load_half(skb, tcp_flags_offset);
        __u16 new_flags = htons(cur_flags | 0x01c0);
        __u16 cur_ace = (cur_flags & 0x01c0) >> 6;

        // connection requesting AccECN by default
        if (cur_ace == 0b111) {
            return TC_ACT_PIPE;
        } else {
            ret = bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
            if (ret) return TC_ACT_PIPE;
            ret = bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);
            if (ret) return TC_ACT_PIPE;
        }
        return TC_ACT_PIPE;
    }

    struct bpf_sock* sk = (struct bpf_sock*)skb->sk;
    if (!sk) {
        return TC_ACT_PIPE;
    }

    SkStorageValue* sks = bpf_sk_storage_get(sk, 0 , 0);
    if (sks) {
        if (sks->l4s.ce_inited) {
            __u16 cur_flags = load_half(skb, tcp_flags_offset);
            __u16 new_flags = htons((cur_flags & 0xfe3f) | ((sks->l4s.ce_count & 7) << 6));

            bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
            bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);

            int ip_tos_offset = isIpv4 ? ETH_IP4_OFFSET(tos) : ETH_IP6_OFFSET(flow_lbl);
            __u8 old_tos = load_byte(skb, ip_tos_offset);
            __u8 new_tos = old_tos | (isIpv4 ? 0x01 : 0x10);

            if (isIpv4) {
                bpf_l3_csum_replace(skb, ETH_IP4_OFFSET(check), htons(old_tos), htons(new_tos), 2);
            }
            bpf_skb_store_bytes(skb, ip_tos_offset, &new_tos, sizeof(new_tos), 0);
       }
    }
    return TC_ACT_PIPE;
}

procedure int update_accecn_counter(struct __sk_buff* skb) {
    if (!is_l4s_enabled()) return 1;
    if (!skb->sk) {
        return 1;
    }

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

    struct bpf_sock* sk = (struct bpf_sock*)skb->sk;
    if (!sk) {
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
            SkStorageValue* sks = bpf_sk_storage_get(sk, 0, 0);
            if (!sks) return 1;

            sks->l4s.ce_count = (ip_ecn == 0b11) ? 0b110 : 0b101;
            sks->l4s.ce_inited = 1;

            int mss_value = parse_tcp_mss_option(skb, hdr_len);
            if (mss_value > 0) sks->l4s.mss = (__u16)mss_value;

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
                    sks->l4s.byte_inited = 1;
                    sks->l4s.e0b = 1;
                    sks->l4s.e1b = 1;
                    sks->l4s.ceb = 0;
                }
            }

            return 1;
        }

        return 1;
    }

    SkStorageValue* sks = bpf_sk_storage_get(sk, 0, 0);
    if (!sks) return 1;

    if (tcph->fin || tcph->rst) return 1;

    if (ip_ecn == 0b11) {
        __u32 ce_packets = 1;
        __u16 mss = sks->l4s.mss;
        if (mss && mss != 0xFFFF) {
            ce_packets = (__u32)(payload_size / mss) + 1;
        }
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

DEFINE_BPF_PROG_KVER_RANGE(schedcls, egress_accecn_rawip, AID_SYSTEM, 6_1, 6_18)
(struct __sk_buff* skb) {
    if (!is_l4s_enabled()) return TC_ACT_PIPE;

    void* data = (void*)(long)skb->data;
    void* data_end = (void*)(long)skb->data_end;

    if (data + sizeof(struct iphdr) > data_end) {
        return TC_ACT_PIPE;
    }

    const bool isIpv4 = skb->protocol == htons(ETH_P_IP);
    const bool isIpv6 = skb->protocol == htons(ETH_P_IPV6);
    struct tcphdr* tcph = NULL;

    if (isIpv4) {
        struct iphdr* ip = data;
        if (ip->protocol == IPPROTO_TCP) {
            if (data + sizeof(struct iphdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip + 1);
        } else {
            return TC_ACT_PIPE;
        }
    } else if (isIpv6) {
        if (data + sizeof(struct ipv6hdr) > data_end) {
            return TC_ACT_PIPE;
        }
        struct ipv6hdr* ip6 = data;
        if (ip6->nexthdr == IPPROTO_TCP) {
            if (data + sizeof(struct ipv6hdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip6 + 1);
        } else {
            return TC_ACT_PIPE;
        }
    } else {
        return TC_ACT_PIPE;
    }

    int tcp_flags_offset = isIpv4 ? IP4_TCP_OFFSET(flags16) : IP6_TCP_OFFSET(flags16);
    int tcp_csum_offset = isIpv4 ? IP4_TCP_OFFSET(check) : IP6_TCP_OFFSET(check);
    int ret = 0;

    // if SYN, then set ACE to 111
    if (tcph->syn && !tcph->ack) {
        __u16 cur_flags = load_half(skb, tcp_flags_offset);
        __u16 new_flags = htons(cur_flags | 0x01c0);
        __u16 cur_ace = (cur_flags & 0x01c0) >> 6;

        // connection requesting AccECN by default
        if (cur_ace == 0b111) {
            return TC_ACT_PIPE;
        } else {
            ret = bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
            if (ret) return TC_ACT_PIPE;
            ret = bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);
            if (ret) return TC_ACT_PIPE;
        }
        return TC_ACT_PIPE;
    }

    struct bpf_sock* sk = (struct bpf_sock*)skb->sk;
    if (!sk) {
        return TC_ACT_PIPE;
    }

    SkStorageValue* sks = bpf_sk_storage_get(sk, 0 , 0);
    if (sks) {
        if (sks->l4s.ce_inited) {
            __u16 cur_flags = load_half(skb, tcp_flags_offset);
            __u16 new_flags = htons((cur_flags & 0xfe3f) | ((sks->l4s.ce_count & 7) << 6));

            bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
            bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);

            int ip_tos_offset = isIpv4 ? IP4_OFFSET(tos) : IP6_OFFSET(flow_lbl);
            __u8 old_tos = load_byte(skb, ip_tos_offset);
            __u8 new_tos = old_tos | (isIpv4 ? 0x01 : 0x10);

            if (isIpv4) {
                bpf_l3_csum_replace(skb, IP4_OFFSET(check), htons(old_tos), htons(new_tos), 2);
            }
            bpf_skb_store_bytes(skb, ip_tos_offset, &new_tos, sizeof(new_tos), 0);
       }
    }
    return TC_ACT_PIPE;
}
