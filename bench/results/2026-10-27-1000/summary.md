# W7 campaign: 2026-10-27-1000

Run log: goodie1857@IMAGESE-JRC0LS7:~/E2SE/E2se4j$ ping -c 5 s3.ap-southeast-2.amazonaws.com
PING s3.ap-southeast-2.amazonaws.com (3.5.166.111) 56(84) bytes of data.
64 bytes from 3.5.166.111 (3.5.166.111): icmp_seq=1 ttl=58 time=2.71 ms
64 bytes from 3.5.166.111 (3.5.166.111): icmp_seq=2 ttl=58 time=2.68 ms
64 bytes from 3.5.166.111 (3.5.166.111): icmp_seq=3 ttl=58 time=2.46 ms
64 bytes from 3.5.166.111 (3.5.166.111): icmp_seq=4 ttl=58 time=2.42 ms
64 bytes from 3.5.166.111 (3.5.166.111): icmp_seq=5 ttl=58 time=3.06 ms

--- s3.ap-southeast-2.amazonaws.com ping statistics ---
5 packets transmitted, 5 received, 0% packet loss, time 4363ms
rtt min/avg/max/mdev = 2.416/2.663/3.063/0.230 ms

Milliseconds; median and IQR (25th to 75th percentile) over the runs after the warm-up (run 0 excluded). One JVM per mode, one open S3 connection; each key-server request opens its own TLS connection.

| configuration | runs (ok) | vault bytes | store median | store IQR | retrieve median | retrieve IQR |
|---|---|---|---|---|---|---|
| plain | 25 (25) | 33002 | 28.3 | 27.1-29.6 | 27.0 | 23.6-28.3 |
| gcm | 25 (25) | 33002 | 137.2 | 132.1-140.3 | 131.1 | 128.4-135.1 |
| head | 25 (25) | 33002 | 228.6 | 223.4-236.2 | 176.3 | 171.7-184.4 |

Lap medians (ms); '-' = the configuration has no such phase.

| phase | lap | plain | gcm | head |
|---|---|---|---|---|
| store | take (unlock) | - | 105.23 | 108.05 |
| store | head_get | - | - | 45.87 |
| store | seal | - | 0.52 | 0.65 |
| store | head_update (+fingerprint) | - | - | 46.99 |
| store | s3_put | 28.25 | 31.41 | 30.06 |
| retrieve | take (unlock) | - | 106.12 | 106.57 |
| retrieve | head_get | - | - | 44.31 |
| retrieve | s3_get | 27.01 | 24.67 | 26.46 |
| retrieve | verify (version + fingerprint) | - | - | 0.02 |
| retrieve | open (seal check) | - | 0.47 | 0.44 |

Head overhead (median with head minus median without):
- store: +91.4 ms (+66.6 % of the no-head store); the two head laps add up to 92.9 ms (head_get 45.87, head_update 46.99)
- retrieve: +45.1 ms (+34.4 % of the no-head retrieve); head_get 44.31 + verify 0.02 = 44.33 ms
  (a difference of medians is not the median of the differences; the lap sums are the direct measure of the head's own cost)

