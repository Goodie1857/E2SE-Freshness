# W7 campaign: 2026-10-26

Milliseconds; median and IQR (25th to 75th percentile) over the runs after the warm-up (run 0 excluded). One JVM per mode, one open S3 connection; each key-server request opens its own TLS connection.

| configuration | runs (ok) | vault bytes | store median | store IQR | retrieve median | retrieve IQR |
|---|---|---|---|---|---|---|
| plain | 25 (25) | 3300 | 27.5 | 26.3-28.5 | 24.4 | 22.9-27.1 |
| gcm | 25 (25) | 3300 | 138.2 | 133.2-143.4 | 131.0 | 127.2-136.2 |
| head | 25 (25) | 3300 | 226.0 | 218.8-232.0 | 175.0 | 168.7-178.9 |

Lap medians (ms); '-' = the configuration has no such phase.

| phase | lap | plain | gcm | head |
|---|---|---|---|---|
| store | take (unlock) | - | 108.60 | 105.34 |
| store | head_get | - | - | 44.11 |
| store | seal | - | 0.24 | 0.25 |
| store | head_update (+fingerprint) | - | - | 46.50 |
| store | s3_put | 27.51 | 28.89 | 27.96 |
| retrieve | take (unlock) | - | 106.18 | 104.43 |
| retrieve | head_get | - | - | 43.91 |
| retrieve | s3_get | 24.37 | 24.65 | 24.12 |
| retrieve | verify (version + fingerprint) | - | - | 0.01 |
| retrieve | open (seal check) | - | 0.30 | 0.19 |

Head overhead (median with head minus median without):
- store: +87.7 ms (+63.5 % of the no-head store); the two head laps add up to 90.6 ms (head_get 44.11, head_update 46.50)
- retrieve: +44.0 ms (+33.6 % of the no-head retrieve); head_get 43.91 + verify 0.01 = 43.92 ms
  (a difference of medians is not the median of the differences; the lap sums are the direct measure of the head's own cost)

