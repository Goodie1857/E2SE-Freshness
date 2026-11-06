# The head's cost with a 95 % interval (bench/figures.py, from the CSV files of the two campaigns)

Bootstrap of the median: 20000 resamples of the 25 runs per configuration, generator seeded with 20261105, warm-up run excluded.
Cost of the head = median with the head minus median without it (sealed vault both times), the two groups resampled independently.

| Vault | Command | Median without head | Median with head | Head adds | 95 % interval | Slowest run without | Fastest run with |
|---|---|---|---|---|---|---|---|
| 100 entries | store | 138.2 | 226.0 | +87.7 ms | 80.1 to 94.5 | 165.8 | 207.8 |
| 100 entries | retrieve | 131.0 | 175.0 | +44.0 ms | 36.9 to 48.8 | 151.0 | 158.3 |
| 1000 entries | store | 137.2 | 228.6 | +91.4 ms | 86.6 to 98.5 | 155.0 | 216.0 |
| 1000 entries | retrieve | 131.1 | 176.3 | +45.1 ms | 40.2 to 49.3 | 142.8 | 162.1 |

Each head request on its own, and the change at ten times the vault (1000 entries minus 100 entries):

| Request | 100 entries, median (95 % interval) | 1000 entries, median (95 % interval) | Change | 95 % interval of the change |
|---|---|---|---|---|
| store: head fetch | 44.1 (43.9 to 44.6) | 45.9 (43.9 to 46.6) | +1.8 ms | -0.3 to +2.6 |
| store: head update | 46.5 (44.5 to 48.2) | 47.0 (44.7 to 49.5) | +0.5 ms | -1.8 to +4.3 |
| retrieve: head fetch | 43.9 (43.8 to 44.3) | 44.3 (43.6 to 45.8) | +0.4 ms | -0.5 to +1.8 |

Reading: an interval of the change that contains zero means the data cannot tell the two sizes apart.

The mean and the standard deviation beside the median, whole commands (the E2SE paper reports means):

| Vault | Configuration | Store: median | Store: mean (standard deviation) | Retrieve: median | Retrieve: mean (standard deviation) |
|---|---|---|---|---|---|
| 100 entries | plain storage | 27.5 | 27.7 (3.0) | 24.4 | 25.4 (4.2) |
| 100 entries | sealed vault, no head | 138.2 | 140.3 (10.7) | 131.0 | 131.6 (7.7) |
| 100 entries | sealed vault with the head | 226.0 | 226.3 (10.9) | 175.0 | 173.5 (8.0) |
| 1000 entries | plain storage | 28.3 | 28.6 (2.7) | 27.0 | 26.2 (4.6) |
| 1000 entries | sealed vault, no head | 137.2 | 136.5 (7.5) | 131.1 | 131.4 (6.0) |
| 1000 entries | sealed vault with the head | 228.6 | 231.5 (11.0) | 176.3 | 177.9 (9.3) |

Reading: the largest gap between a mean and its median is 2.9 ms. The head's cost from the means: 100 entries: store +86.0 ms, retrieve +41.9 ms; 1000 entries: store +95.0 ms, retrieve +46.5 ms.
