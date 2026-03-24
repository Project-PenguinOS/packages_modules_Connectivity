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

DEFINE_BPF_MAP(l4s_conn_counter, ARRAY, uint32_t, uint32_t, 1)
DEFINE_BPF_SK_STORAGE(sk_l4s_storage, L4SStorage)
DEFINE_BPF_MAP_NO_NETD(l4s_accecn_enabled_map, ARRAY, uint32_t, bool, 1)

static inline __attribute__((always_inline)) int
find_accecn_options_offset(struct __sk_buff *skb, uint8_t offset) {
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

static inline __attribute__((always_inline)) int
parse_tcp_mss_option(struct __sk_buff *skb, uint8_t offset) {
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

static inline __attribute__((always_inline)) int
is_l4s_enabled() {
    uint32_t status_key = 0;
    bool *l4s_status_ptr = bpf_l4s_accecn_enabled_map_lookup_elem(&status_key);
    return l4s_status_ptr && *l4s_status_ptr;
}

static const struct {
    __u8 kind;
    __u8 length;
    __u8 data[9];
} __attribute__((packed)) tcp_accecn_option = {
    .kind = 174,
    .length = sizeof tcp_accecn_option,
    .data = {},
};

DEFINE_BPF_PROG_KVER(sockops, accecn_option, AID_SYSTEM, 6_1)
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
            if (skops->skb_tcp_flags & TCP_FLAG8_SYN) {
                break;
            }

            L4SStorage* st = bpf_sk_l4s_storage_get(skops->sk, 0, 0);
            if (!st || !st->byte_inited) {
                break;
            }
            bpf_reserve_hdr_opt(skops, sizeof tcp_accecn_option, 0);
            break;
        }
        case BPF_SOCK_OPS_WRITE_HDR_OPT_CB:
        {
            if (skops->skb_tcp_flags & TCP_FLAG8_SYN) {
                break;
            }

            L4SStorage* st = bpf_sk_l4s_storage_get(skops->sk, 0, 0);
            if (!st || !st->byte_inited) {
                break;
            }
            bpf_store_hdr_opt(skops, &tcp_accecn_option, sizeof tcp_accecn_option, 0);
            break;
        }
        default:
            break;
    }
    return 1;
}

DEFINE_BPF_PROG_KVER(schedcls, egress_accecn_eth, AID_SYSTEM, 6_1)
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
    int hdr_len = sizeof(struct ethhdr);

    if (isIpv4) {
        struct iphdr* ip = data + ETH_HLEN;
        if (ip->protocol == IPPROTO_TCP) {
            if (data + sizeof(struct ethhdr) + sizeof(struct iphdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip + 1);
            hdr_len += sizeof(struct iphdr);
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
            hdr_len += sizeof(struct ipv6hdr);
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

    L4SStorage* st = bpf_sk_l4s_storage_get(sk, 0 , 0);
    if (st) {
        if (st->ce_inited) {
            __u16 cur_flags = load_half(skb, tcp_flags_offset);
            __u16 new_flags = htons((cur_flags & 0xfe3f) | ((st->ce_count & 7) << 6));

            bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
            bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);

            int ip_tos_offset = isIpv4 ? ETH_IP4_OFFSET(tos) : ETH_IP6_OFFSET(flow_lbl);
            __u8 old_tos = load_byte(skb, ip_tos_offset);
            __u8 new_tos = old_tos | (isIpv4 ? 0x01 : 0x10);

            if (isIpv4) {
                bpf_l3_csum_replace(skb, ETH_IP4_OFFSET(check), htons(old_tos), htons(new_tos), 2);
            }
            bpf_skb_store_bytes(skb, ip_tos_offset, &new_tos, sizeof(new_tos), 0);

            if (st->byte_inited) {
                __u8 ace_option[12] = { 0 };
                __u32 e0b_val = htonl((__u32)(st->e0b & 0x0000000000FFFFFF)) >> 8;
                __u32 ceb_val = htonl((__u32)(st->ceb & 0x0000000000FFFFFF)) >> 8;
                __u32 e1b_val = htonl((__u32)(st->e1b & 0x0000000000FFFFFF)) >> 8;
                __builtin_memcpy(&ace_option[0], &e0b_val, 3);
                __builtin_memcpy(&ace_option[3], &ceb_val, 3);
                __builtin_memcpy(&ace_option[6], &e1b_val, 3);

                int offset = find_accecn_options_offset(skb, hdr_len);
                if (offset < 0) return TC_ACT_PIPE;

                __u16 current_checksum = ntohs(load_half(skb, tcp_csum_offset));
                int64_t res = bpf_csum_diff(NULL, 0, (__be32*)ace_option, 12, current_checksum);
                if (res < 0) return TC_ACT_PIPE;

                ret = bpf_l4_csum_replace(skb, tcp_csum_offset, 0, (__u64)res, 0);
                if (ret) return TC_ACT_PIPE;

                ret = bpf_skb_store_bytes(skb, hdr_len + offset + 2, &e1b_val, 3, BPF_F_RECOMPUTE_CSUM);
                if (ret) return TC_ACT_PIPE;

                ret = bpf_skb_store_bytes(skb, hdr_len + offset + 5, &ceb_val, 3, BPF_F_RECOMPUTE_CSUM);
                if (ret) return TC_ACT_PIPE;

                ret = bpf_skb_store_bytes(skb, hdr_len + offset + 8, &e0b_val, 3, BPF_F_RECOMPUTE_CSUM);
                if (ret) return TC_ACT_PIPE;
          }
       }
    }
    return TC_ACT_PIPE;
}

DEFINE_BPF_PROG_KVER(ingress, accecn_common, AID_SYSTEM, 6_1)
(struct __sk_buff* skb)
{
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
        __u16 ace = (flags & 0x01c0) >> 6;

        if (ace == 0b010 || ace == 0b011 || ace == 0b100 || ace == 0b110) {
            L4SStorage* st = bpf_sk_l4s_storage_get(sk, 0, BPF_SK_STORAGE_GET_F_CREATE);
            if (!st) {
                return 1;
            }

            st->ce_count = (ip_ecn == 0b11) ? 0b110 : 0b101;
            st->ce_inited = 1;

            int mss_value = parse_tcp_mss_option(skb, hdr_len);
            if (mss_value > 0) st->mss = (__u16)mss_value;

            uint32_t conn_key = 0;
            uint32_t* conn_count = bpf_l4s_conn_counter_lookup_elem(&conn_key);
            uint32_t oneConnection = 1;
            if (!conn_count) {
                bpf_l4s_conn_counter_update_elem(&conn_key, &oneConnection, 0);
            } else {
                __sync_fetch_and_add(conn_count, oneConnection);
            }

            if (!st->byte_inited) {
                int is_accecn = find_accecn_options_offset(skb, hdr_len);
                if (is_accecn != -1) {
                    st->byte_inited = 1;
                    st->e0b = 1;
                    st->e1b = 1;
                    st->ceb = 0;
                }
            }

            return 1;
        }

        return 1;
    }

    L4SStorage* st = bpf_sk_l4s_storage_get(sk, 0, 0);
    if (!st) {
        return 1;
    }

    if (tcph->fin || tcph->rst) {
        bpf_sk_l4s_storage_delete(sk);
        return 1;
    }

    if (ip_ecn == 0b11) {
        __u32 ce_packets = 1;
        __u16 mss = st->mss;
        if (mss && mss != 0xFFFF) {
            ce_packets = (__u32)(payload_size / mss) + 1;
        }
        __sync_fetch_and_add(&st->ce_count, ce_packets);
    }

    if (st->byte_inited) {
        if (ip_ecn == 0b11) {
            __sync_fetch_and_add(&st->ceb, payload_size);
        }
        else if (ip_ecn == 0b10) {
           __sync_fetch_and_add(&st->e0b, payload_size);
        }
        else if (ip_ecn == 0b01) {
           __sync_fetch_and_add(&st->e1b, payload_size);
        }
    }
    return 1;
}

DEFINE_BPF_PROG_KVER(schedcls, egress_accecn_rawip, AID_SYSTEM, 6_1)
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
    int hdr_len = 0;

    if (isIpv4) {
        struct iphdr* ip = data;
        if (ip->protocol == IPPROTO_TCP) {
            if (data + sizeof(struct iphdr) + sizeof(struct tcphdr) > data_end) {
                return TC_ACT_PIPE;
            }
            tcph = (void*)(ip + 1);
            hdr_len += sizeof(struct iphdr);
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
            hdr_len += sizeof(struct ipv6hdr);
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

    L4SStorage* st = bpf_sk_l4s_storage_get(sk, 0 , 0);
    if (st) {
        if (st->ce_inited) {
            __u16 cur_flags = load_half(skb, tcp_flags_offset);
            __u16 new_flags = htons((cur_flags & 0xfe3f) | ((st->ce_count & 7) << 6));

            bpf_l4_csum_replace(skb, tcp_csum_offset, htons(cur_flags), new_flags, 2);
            bpf_skb_store_bytes(skb, tcp_flags_offset, &new_flags, sizeof(new_flags), 0);

            int ip_tos_offset = isIpv4 ? IP4_OFFSET(tos) : IP6_OFFSET(flow_lbl);
            __u8 old_tos = load_byte(skb, ip_tos_offset);
            __u8 new_tos = old_tos | (isIpv4 ? 0x01 : 0x10);

            if (isIpv4) {
                bpf_l3_csum_replace(skb, IP4_OFFSET(check), htons(old_tos), htons(new_tos), 2);
            }
            bpf_skb_store_bytes(skb, ip_tos_offset, &new_tos, sizeof(new_tos), 0);

            if (st->byte_inited) {
                __u8 ace_option[12] = { 0 };
                __u32 e0b_val = htonl((__u32)(st->e0b & 0x0000000000FFFFFF)) >> 8;
                __u32 ceb_val = htonl((__u32)(st->ceb & 0x0000000000FFFFFF)) >> 8;
                __u32 e1b_val = htonl((__u32)(st->e1b & 0x0000000000FFFFFF)) >> 8;
                __builtin_memcpy(&ace_option[0], &e0b_val, 3);
                __builtin_memcpy(&ace_option[3], &ceb_val, 3);
                __builtin_memcpy(&ace_option[6], &e1b_val, 3);

                int offset = find_accecn_options_offset(skb, hdr_len);
                if (offset < 0) return TC_ACT_PIPE;

                __u16 current_checksum = ntohs(load_half(skb, tcp_csum_offset));
                int64_t res = bpf_csum_diff(NULL, 0, (__be32*)ace_option, 12, current_checksum);
                if (res < 0) return TC_ACT_PIPE;

                ret = bpf_l4_csum_replace(skb, tcp_csum_offset, 0, (__u64)res, 0);
                if (ret) return TC_ACT_PIPE;

                ret = bpf_skb_store_bytes(skb, hdr_len + offset + 2, &e1b_val, 3, BPF_F_RECOMPUTE_CSUM);
                if (ret) return TC_ACT_PIPE;

                ret = bpf_skb_store_bytes(skb, hdr_len + offset + 5, &ceb_val, 3, BPF_F_RECOMPUTE_CSUM);
                if (ret) return TC_ACT_PIPE;

                ret = bpf_skb_store_bytes(skb, hdr_len + offset + 8, &e0b_val, 3, BPF_F_RECOMPUTE_CSUM);
                if (ret) return TC_ACT_PIPE;
          }
       }
    }
    return TC_ACT_PIPE;
}
