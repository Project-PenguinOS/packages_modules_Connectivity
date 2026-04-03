#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

import inspect
import time
from android.platform.test.annotations import CddTest, VsrTest
from mobly import asserts
from net_tests_utils.host.python import adb_utils, apf_test_base, apf_utils, assert_utils
from scapy.contrib.igmpv3 import IGMPv3, IGMPv3gr, IGMPv3mq, IGMPv3mr
from scapy.layers.dns import DNS, DNSRR, DNSRRSRV, dns_compress
from scapy.layers.inet import ICMP, IP, IPOption_Router_Alert, UDP
from scapy.layers.inet6 import (
    ICMPv6EchoReply,
    ICMPv6EchoRequest,
    ICMPv6MLDMultAddrRec,
    ICMPv6MLQuery2,
    ICMPv6MLReport2,
    ICMPv6NDOptDstLLAddr,
    ICMPv6NDOptSrcLLAddr,
    ICMPv6ND_NA,
    ICMPv6ND_NS,
    IPv6,
    IPv6ExtHdrHopByHop,
    RouterAlert,
)
from scapy.layers.l2 import ARP, Ether

APFV6_VERSION = 6000
ARP_OFFLOAD_REPLY_LEN = 60
MDNS_RCLASS_IN_FLUSH_CACHE = 0x8001


@VsrTest(
    requirements=[
        'VSR-5.3.12-002',
        'VSR-5.3.12-005',
        'VSR-5.3.12-012',
        'VSR-5.3.12-013',
        'VSR-5.3.12-014',
        'VSR-5.3.12-015',
        'VSR-5.3.12-016',
        'VSR-5.3.12-017',
    ]
)
@CddTest(requirements=['7.4.5.2/C-0-5', '7.4.2/C-1-4'])
class ApfV6Test(apf_test_base.ApfTestBase):

  def setup_class(self):
    super().setup_class()

    # Skip tests for APF version < 6000
    apf_utils.assume_apf_version_support_at_least(
        self.clientDevice, self.client_iface_name, APFV6_VERSION
    )

  def teardown_class(self):
    # force to stop capture on the server device if any test case failed
    try:
      if hasattr(self, 'server_iface_name') and self.server_iface_name:
        apf_utils.stop_capture_packets(
            self.serverDevice, self.server_iface_name
        )
    except assert_utils.UnexpectedBehaviorError:
      pass
    super().teardown_class()

  def test_unicast_arp_request_offload(self):
    self.get_and_expect_ipv4_addresses_exist()

    # Longer wait time is required for APF to become active in CTS test suite.
    time.sleep(apf_test_base.APF_ACTIVATION_WAIT_TIME_SEC)
    self.expect_apf_offload_enabled('ARP')

    eth = Ether(src=self.server_mac_address, dst=self.client_mac_address)
    arp = ARP(
        op=1,
        psrc=self.server_ipv4_addresses[0],
        pdst=self.client_ipv4_addresses[0],
        hwsrc=self.server_mac_address,
    )
    arp_request = bytes(eth / arp).hex()

    eth = Ether(src=self.client_mac_address, dst=self.server_mac_address)
    arp = ARP(
        op=2,
        psrc=self.client_ipv4_addresses[0],
        pdst=self.server_ipv4_addresses[0],
        hwsrc=self.client_mac_address,
        hwdst=self.server_mac_address,
    )
    expected_arp_reply = bytes(eth / arp).hex()

    # Add zero padding up to 60 bytes, since APFv6 ARP offload always sent out 60 bytes reply
    expected_arp_reply = expected_arp_reply.ljust(
        ARP_OFFLOAD_REPLY_LEN * 2, '0'
    )

    self.send_packet_and_expect_counter_increased(
        arp_request,
        'DROPPED_ARP_REQUEST_REPLIED',
        expected_arp_reply,
        test_case_name=inspect.currentframe().f_code.co_name,
    )

  def test_non_dad_ipv6_neighbor_solicitation_offload(self):
    self.get_and_expect_ipv6_addresses_exist()

    # Longer wait time is required for APF to become active in CTS test suite.
    time.sleep(apf_test_base.APF_ACTIVATION_WAIT_TIME_SEC)
    self.expect_apf_offload_enabled('ND')

    eth = Ether(src=self.server_mac_address, dst=self.client_mac_address)
    ip = IPv6(
        src=self.server_ipv6_addresses[0], dst=self.client_ipv6_addresses[0]
    )
    icmpv6 = ICMPv6ND_NS(tgt=self.client_ipv6_addresses[0])
    opt = ICMPv6NDOptSrcLLAddr(lladdr=self.server_mac_address)
    neighbor_solicitation = bytes(eth / ip / icmpv6 / opt).hex()

    eth = Ether(src=self.client_mac_address, dst=self.server_mac_address)
    ip = IPv6(
        src=self.client_ipv6_addresses[0], dst=self.server_ipv6_addresses[0]
    )
    icmpv6 = ICMPv6ND_NA(tgt=self.client_ipv6_addresses[0], R=1, S=1, O=1)
    opt = ICMPv6NDOptDstLLAddr(lladdr=self.client_mac_address)
    expected_neighbor_advertisement = bytes(eth / ip / icmpv6 / opt).hex()
    self.send_packet_and_expect_counter_increased(
        neighbor_solicitation,
        'DROPPED_IPV6_NS_REPLIED_NON_DAD',
        expected_neighbor_advertisement,
        test_case_name=inspect.currentframe().f_code.co_name,
    )

  @apf_utils.at_least_B()
  def test_ipv4_icmp_echo_request_offload(self):
    self.get_and_expect_ipv4_addresses_exist()

    # Longer wait time is required for APF to become active in CTS test suite.
    time.sleep(apf_test_base.APF_ACTIVATION_WAIT_TIME_SEC)
    self.expect_apf_offload_enabled('Ping4')

    eth = Ether(src=self.server_mac_address, dst=self.client_mac_address)
    ip = IP(
        src=self.server_ipv4_addresses[0], dst=self.client_ipv4_addresses[0]
    )
    icmp = ICMP(id=1, seq=123)
    echo_request = bytes(eth / ip / icmp / b'hello').hex()

    eth = Ether(src=self.client_mac_address, dst=self.server_mac_address)
    ip = IP(
        src=self.client_ipv4_addresses[0], dst=self.server_ipv4_addresses[0]
    )
    icmp = ICMP(type=0, id=1, seq=123)
    expected_echo_reply = bytes(eth / ip / icmp / b'hello').hex()
    self.send_packet_and_expect_counter_increased(
        echo_request,
        'DROPPED_IPV4_PING_REQUEST_REPLIED',
        expected_echo_reply,
        test_case_name=inspect.currentframe().f_code.co_name,
    )

  @apf_utils.at_least_B()
  @apf_utils.apf_ram_at_least(3000)
  def test_ipv6_icmp_echo_request_offload(self):
    self.get_and_expect_ipv6_addresses_exist()

    # Longer wait time is required for APF to become active in CTS test suite.
    time.sleep(apf_test_base.APF_ACTIVATION_WAIT_TIME_SEC)
    self.expect_apf_offload_enabled('Ping6')

    eth = Ether(src=self.server_mac_address, dst=self.client_mac_address)
    ip = IPv6(
        src=self.server_ipv6_addresses[0], dst=self.client_ipv6_addresses[0]
    )
    icmp = ICMPv6EchoRequest(id=1, seq=123)
    echo_request = bytes(eth / ip / icmp / b'hello').hex()

    hop_limit = apf_utils.get_hop_limit(
        self.clientDevice, self.client_iface_name
    )
    eth = Ether(src=self.client_mac_address, dst=self.server_mac_address)
    ip = IPv6(
        src=self.client_ipv6_addresses[0],
        dst=self.server_ipv6_addresses[0],
        hlim=hop_limit,
    )
    icmp = ICMPv6EchoReply(id=1, seq=123)
    expected_echo_reply = bytes(eth / ip / icmp / b'hello').hex()

    self.send_packet_and_expect_counter_increased(
        echo_request,
        'DROPPED_IPV6_ICMP6_ECHO_REQUEST_REPLIED',
        expected_echo_reply,
        test_case_name=inspect.currentframe().f_code.co_name,
    )

  @apf_utils.at_least_B()
  def test_igmpv3_general_query_offload(self):
    self.get_and_expect_ipv4_addresses_exist()

    mcast_addrs = ['239.0.0.1']

    self.client.createMulticastSocket(self.client_iface_name)
    for addr in mcast_addrs:
      self.client.joinMulticastGroup(addr)

    # Longer wait time is required for APF to become active in CTS test suite.
    time.sleep(apf_test_base.APF_ACTIVATION_WAIT_TIME_SEC)
    self.expect_apf_offload_enabled('IGMP')
    # use unicast to replace multicast ether dst to prevent flaky due to DTIM skip
    ether = Ether(src=self.server_mac_address, dst=self.client_mac_address)
    ip = IP(
        src=self.server_ipv4_addresses[0],
        dst='224.0.0.1',
        options=[IPOption_Router_Alert()],
    )
    igmp = IGMPv3(type=0x11) / IGMPv3mq()
    igmpv3_general_query = bytes(ether / ip / igmp).hex()

    ether = Ether(src=self.client_mac_address, dst='01:00:5e:00:00:16')
    ip = IP(
        src=self.client_ipv4_addresses[0],
        dst='224.0.0.22',
        options=[IPOption_Router_Alert()],
        id=0,
        flags='DF',
    )
    igmpv3_hdr = IGMPv3(type=0x22)
    device_mcast_addrs = apf_utils.get_ipv4_multicast_addresses(
        self.clientDevice, self.client_iface_name
    )
    # Check if all mcast_addrs are in device_mcast_addrs
    missing_addrs = [
        addr for addr in mcast_addrs if addr not in device_mcast_addrs
    ]
    if missing_addrs:
      asserts.fail(
          f'Expected multicast addresses {missing_addrs} not found in device '
          f'multicast addresses: {device_mcast_addrs}'
      )

    mcast_records = []
    # Sort multicast addresses
    sorted_device_mcast_addrs = self.client.sortMulticastAddresses(
        device_mcast_addrs
    )
    for addr in sorted_device_mcast_addrs:
      mcast_records.append(IGMPv3gr(rtype=2, maddr=addr))

    igmp = IGMPv3mr(records=mcast_records)
    expected_igmpv3_report = bytes(ether / ip / igmpv3_hdr / igmp).hex()
    try:
      self.send_packet_and_expect_counter_increased(
          igmpv3_general_query,
          'DROPPED_IGMP_V3_GENERAL_QUERY_REPLIED',
          expected_igmpv3_report,
          test_case_name=inspect.currentframe().f_code.co_name,
      )
    finally:
      for addr in mcast_addrs:
        self.client.leaveMulticastGroup(addr)
      self.client.destroyMulticastSocket()

  @apf_utils.at_least_B()
  @apf_utils.apf_ram_at_least(3000)
  def test_mldv2_general_query_offload(self):
    self.get_and_expect_ipv6_addresses_exist()

    # Longer wait time is required for APF to become active in CTS test suite.
    time.sleep(apf_test_base.APF_ACTIVATION_WAIT_TIME_SEC)
    self.expect_apf_offload_enabled('MLD')

    # use unicast to replace multicast ether dst to prevent flaky due to DTIM skip
    ether = Ether(src=self.server_mac_address, dst=self.client_mac_address)
    server_link_local_ip = next(
        ip for ip in self.server_ipv6_addresses if ip.startswith('fe80')
    )
    ip = IPv6(src=server_link_local_ip, dst='ff02::1', hlim=1)
    hopOpts = IPv6ExtHdrHopByHop(options=[RouterAlert(otype=5)])
    mld = ICMPv6MLQuery2()
    mldv2_general_query = bytes(ether / ip / hopOpts / mld).hex()

    ether = Ether(src=self.client_mac_address, dst='33:33:00:00:00:16')
    client_link_local_ip = next(
        ip for ip in self.client_ipv6_addresses if ip.startswith('fe80')
    )
    ip = IPv6(src=client_link_local_ip, dst='ff02::16', hlim=1)

    mcast_addrs = apf_utils.get_exclude_all_host_ipv6_multicast_addresses(
        self.clientDevice, self.client_iface_name
    )

    mld_records = []
    # Sort multicast addresses
    sorted_mcast_addrs = self.client.sortMulticastAddresses(mcast_addrs)
    for addr in sorted_mcast_addrs:
      mld_records.append(ICMPv6MLDMultAddrRec(dst=addr, rtype=2))
    mld = ICMPv6MLReport2(records=mld_records)
    expected_mldv2_report = bytes(ether / ip / hopOpts / mld).hex()
    self.send_packet_and_expect_counter_increased(
        mldv2_general_query,
        'DROPPED_IPV6_MLD_V2_GENERAL_QUERY_REPLIED',
        expected_mldv2_report,
        test_case_name=inspect.currentframe().f_code.co_name,
    )

  def _generate_mdns_dns_reply_payload(
      self,
      service_name,
      hostname,
      txt_content,
      dnsrr_type,
      server_ip,
  ):
    # service_name is in the format of <instance_name>.<service_type>
    service_type = '.'.join(service_name.split('.')[1:])
    answers = [
        DNSRR(
            rrname=service_type,
            type='PTR',
            ttl=120,
            rdata=service_name,
        )
    ]
    additional_record = [
        DNSRRSRV(
            rrname=service_name,
            rclass=MDNS_RCLASS_IN_FLUSH_CACHE,
            port=12345,
            target=hostname,
            ttl=120,
        ),
        DNSRR(rrname=service_name, type='TXT', ttl=120, rdata=txt_content),
        DNSRR(rrname=hostname, type=dnsrr_type, ttl=120, rdata=server_ip),
    ]
    return dns_compress(
        DNS(qr=1, aa=1, rd=0, qd=None, an=answers, ar=additional_record)
    )

  def _test_mdns_reply_filter_service_discovery(
      self, ip_version, test_case_name=''
  ):
    if ip_version == 4:
      self.get_and_expect_ipv4_addresses_exist()
      apf_filter_name = 'Mdns4'
      dst_ip = self.server_ipv4_addresses[0]
      mdns_ip = '224.0.0.251'
      ip = IP(src=dst_ip, dst=mdns_ip)
      dnsrr_type = 'A'
    elif ip_version == 6:
      self.get_and_expect_ipv6_addresses_exist()
      apf_filter_name = 'Mdns6'
      dst_ip = self.server_ipv6_addresses[0]
      mdns_ip = 'ff02::fb'
      ip = IPv6(src=dst_ip, dst=mdns_ip)
      dnsrr_type = 'AAAA'
    else:
      asserts.fail('Invalid ip_version')

    self.check_and_mdns_reply_filter_enabled()
    self.expect_no_multicast_lock_held()

    mdns_client = self.clientDevice
    mdns_client.connectivity_multi_devices_snippet.startMDnsServiceDiscovery()

    time.sleep(apf_test_base.APF_ACTIVATION_WAIT_TIME_SEC)
    self.expect_apf_mdns_reply_filter_enabled(apf_filter_name, test_case_name)

    try:
      service_name = 'MultiDevicesTest._multi_devices._tcp.local'
      hostname = 'multidevicestest12345.local'
      txt_content = 'key=value'
      ether = Ether(src=self.server_mac_address, dst=self.client_mac_address)
      udp = UDP(sport=5353, dport=5353)
      matching_dns = self._generate_mdns_dns_reply_payload(
          service_name, hostname, txt_content, dnsrr_type, dst_ip
      )
      matching_packet = bytes(ether / ip / udp / matching_dns).hex()

      self.send_packet_and_expect_counter_increased(
          packet=matching_packet,
          counter_name='PASSED_MDNS',
          test_case_name=test_case_name,
          max_retries=20,
          retry_interval_sec=5,
      )
      mdns_client.connectivity_multi_devices_snippet.ensureMDnsServiceDiscovered()

      non_matching_service_name = 'NonMatchingService._different._tcp.local'
      non_matching_hostname = 'nonmatchinghost.local'
      non_matching_dns = self._generate_mdns_dns_reply_payload(
          non_matching_service_name,
          non_matching_hostname,
          txt_content,
          dnsrr_type,
          dst_ip,
      )
      non_matching_packet = bytes(ether / ip / udp / non_matching_dns).hex()
      self.send_packet_and_expect_counter_increased(
          packet=non_matching_packet,
          counter_name='DROPPED_MDNS_REPLY_FILTERED',
          test_case_name=test_case_name,
          max_retries=20,
          retry_interval_sec=5,
      )
    finally:
      mdns_client.connectivity_multi_devices_snippet.stopMDnsServiceDiscovery()

  @apf_utils.at_least_B()
  @apf_utils.apf_ram_at_least(3000)
  def test_ipv4_mdns_reply_filter_service_discovery(self):
    self._test_mdns_reply_filter_service_discovery(
        ip_version=4, test_case_name=inspect.currentframe().f_code.co_name
    )

  @apf_utils.at_least_B()
  @apf_utils.apf_ram_at_least(3000)
  def test_ipv6_mdns_reply_filter_service_discovery(self):
    self._test_mdns_reply_filter_service_discovery(
        ip_version=6, test_case_name=inspect.currentframe().f_code.co_name
    )
