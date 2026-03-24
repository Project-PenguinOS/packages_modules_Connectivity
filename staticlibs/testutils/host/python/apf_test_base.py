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

import time
from mobly import asserts
from net_tests_utils.host.python import adb_utils, apf_utils, assert_utils, multi_devices_test_base, tether_utils
from net_tests_utils.host.python.tether_utils import UpstreamType

APF_ACTIVATION_WAIT_TIME_SEC = 5


class ApfTestBase(multi_devices_test_base.MultiDevicesTestBase):

  def _start_tcpdump(self, ad, iface_name, output_file):
    if ad.is_rootable:
      apf_utils.start_tcpdump_capture(ad, iface_name, output_file)

  def _stop_tcpdump(self, ad, iface_name, file_name, src_path):
    if ad.is_rootable:
      apf_utils.stop_tcpdump_capture(ad, iface_name)
      apf_utils.pull_file_from_device(
          ad=ad,
          file_name=file_name,
          extension_name='pcap',
          src_path=src_path,
          dst_path=self.current_test_info.output_path,
      )
      adb_utils.adb_shell(ad, f'rm -f {src_path}')

  def setup_class(self):
    super().setup_class()

    # Check test preconditions.
    asserts.abort_class_if(
        not self.client.isAtLeastV(),
        'Do not enforce the test until V+ since chipset potential bugs are'
        ' expected to be fixed on V+ releases.',
    )
    tether_utils.assume_hotspot_test_preconditions(
        self.serverDevice, self.clientDevice, UpstreamType.NONE
    )
    asserts.abort_class_if(
        not apf_utils.is_send_raw_packet_downstream_supported(
            self.serverDevice
        ),
        'NetworkStack is too old to support send raw packet, skip test.',
    )

    asserts.abort_class_if(
        self.client.hasAutomotiveFeature(),
        'APF GMS-VSR requirements do not apply to automotive devices, skip'
        ' test.',
    )

    # TODO(b/450670091): Run APF tests on desktop devices once the feature is ready.
    asserts.abort_class_if(
        self.client.hasPCFeature(),
        'APF is not implemented on desktop devices, skip test.',
    )

    # Fetch device properties and storing them locally for later use.
    # TODO: refactor to separate instances to store client and server device
    self.server_iface_name, client_network = (
        tether_utils.setup_hotspot_and_client_for_upstream_type(
            self.serverDevice, self.clientDevice, UpstreamType.NONE
        )
    )
    self.client_iface_name = self.client.getInterfaceNameFromNetworkHandle(
        client_network
    )
    self.server_mac_address = apf_utils.get_hardware_address(
        self.serverDevice, self.server_iface_name
    )
    self.client_mac_address = apf_utils.get_hardware_address(
        self.clientDevice, self.client_iface_name
    )

    # Enable doze mode to activate APF.
    adb_utils.set_doze_mode(self.clientDevice, True)

    self._start_tcpdump(
        self.clientDevice,
        self.client_iface_name,
        '/data/local/tmp/client_capture.pcap',
    )
    self._start_tcpdump(
        self.serverDevice,
        self.server_iface_name,
        '/data/local/tmp/server_capture.pcap',
    )

  def teardown_class(self):
    self._stop_tcpdump(
        self.clientDevice,
        self.client_iface_name,
        'client_capture',
        '/data/local/tmp/client_capture.pcap',
    )
    self._stop_tcpdump(
        self.serverDevice,
        self.server_iface_name,
        'server_capture',
        '/data/local/tmp/server_capture.pcap',
    )
    adb_utils.set_doze_mode(self.clientDevice, False)
    tether_utils.cleanup_tethering_for_upstream_type(
        self.serverDevice, UpstreamType.NONE
    )

  def get_and_expect_ipv4_addresses_exist(self):
    self.server_ipv4_addresses = apf_utils.get_ipv4_addresses(
        self.serverDevice, self.server_iface_name
    )

    asserts.assert_true(
        self.server_ipv4_addresses,
        'Server does not have IPv4 address, fail the test.',
    )

    self.client_ipv4_addresses = apf_utils.get_ipv4_addresses(
        self.clientDevice, self.client_iface_name
    )
    asserts.assert_true(
        self.client_ipv4_addresses,
        'Client does not have IPv4 address, fail the test.',
    )

  def get_and_expect_ipv6_addresses_exist(self):
    self.server_ipv6_addresses = apf_utils.get_non_tentative_ipv6_addresses(
        self.serverDevice, self.server_iface_name
    )

    asserts.assert_true(
        self.server_ipv6_addresses,
        'Server does not have IPv6 address, fail the test.',
    )

    self.client_ipv6_addresses = apf_utils.get_non_tentative_ipv6_addresses(
        self.clientDevice, self.client_iface_name
    )
    asserts.assert_true(
        self.client_ipv6_addresses,
        'Client does not have IPv6 address, fail the test.',
    )

  def _check_counter_and_packet(
      self,
      counter_name: str,
      count_before: int,
      expected_reply_packet: str,
      results: dict[str, int],
  ) -> bool:
    results['last_apf_counter_value'] = apf_utils.get_apf_counter(
        self.clientDevice, self.client_iface_name, counter_name
    )
    if expected_reply_packet:
      results['last_matched_receive_pkt_count'] = (
          apf_utils.get_matched_packet_counts(
              self.serverDevice, self.server_iface_name, expected_reply_packet
          )
      )

    is_counter_increased = results['last_apf_counter_value'] > count_before
    is_reply_packet_received = not expected_reply_packet or (
        results['last_matched_receive_pkt_count'] > 0
    )
    return is_counter_increased and is_reply_packet_received

  def send_packet_and_expect_counter_increased(
      self,
      packet: str,
      counter_name: str,
      expected_reply_packet: str = None,
      test_case_name: str = '',
      max_retries: int = 10,
      retry_interval_sec: int = 3,
  ) -> None:
    """Sends a packet and expects the APF counter to increase.

    If expected_reply_packet is not None, the method will also check if the
    reply
    packet is received along with checking the counter.
    """
    results = {'last_apf_counter_value': 0, 'last_matched_receive_pkt_count': 0}
    count_before_test = 0

    try:
      if expected_reply_packet:
        apf_utils.start_capture_packets(
            self.serverDevice, self.server_iface_name
        )

      count_before_test = apf_utils.get_apf_counter(
          self.clientDevice, self.client_iface_name, counter_name
      )
      results['last_apf_counter_value'] = count_before_test

      apf_utils.send_raw_packet_downstream(
          self.serverDevice, self.server_iface_name, packet
      )

      assert_utils.expect_with_retry(
          predicate=lambda: self._check_counter_and_packet(
              counter_name,
              count_before_test,
              expected_reply_packet,
              results,
          ),
          retry_action=lambda: apf_utils.send_raw_packet_downstream(
              self.serverDevice, self.server_iface_name, packet
          ),
          max_retries=max_retries,
          retry_interval_sec=retry_interval_sec,
      )

    except (assert_utils.UnexpectedBehaviorError, Exception) as e:
      is_counter_increased = (
          results['last_apf_counter_value'] > count_before_test
      )
      is_reply_packet_received = not expected_reply_packet or (
          results['last_matched_receive_pkt_count'] > 0
      )
      errors = []
      if not is_counter_increased:
        errors.append(
            f'APF counter "{counter_name}" did not increase.'
            f' (before: {count_before_test}, last: {results["last_count"]})'
        )
      if expected_reply_packet and not is_reply_packet_received:
        errors.append(
            f'Expected reply packet not received: {expected_reply_packet}.'
            f' (last: {results["last_matched_receive_pkt_count"]})'
        )

      msg = f'{test_case_name} failed: {" ".join(errors)}'
      asserts.fail(f'{msg} Original error: {e}')

    finally:
      if expected_reply_packet:
        apf_utils.stop_capture_packets(
            self.serverDevice, self.server_iface_name
        )

  def expect_apf_offload_enabled(self, offload: str):
    assert_utils.expect_with_retry(
        lambda: offload
        in apf_utils.get_apf_config_from_cmd(
            self.clientDevice, self.client_iface_name
        )
    )
