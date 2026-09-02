# Reference integrity record

## Read-only sources

The following supplied directories were treated as read-only:

| Reference | Location |
| --- | --- |
| Ruthenium | C:/Users/Admin/Downloads/Project PHOENIUM/Ruthenium-main/Ruthenium-main |
| Folia | C:/Users/Admin/Downloads/Project PHOENIUM/Folia-ver-26.2.x/Folia-ver-26.2.x |
| PandaAntiLag | C:/Users/Admin/Downloads/Project PHOENIUM/PandaAntiLag-panda-anti-lag-2.2.0_1.21.11/PandaAntiLag-panda-anti-lag-2.2.0_1.21.11 |

The pre-audit inventory was 5,167 files totaling 76,130,499 bytes. Content comparisons were made against isolated upstream clones. The only differences found for the supplied Folia and Ruthenium references were CRLF/LF line endings in Windows batch files. The supplied PandaAntiLag copy corresponds to a pre-26.2 upstream source snapshot; its only local/upstream difference at that revision was a batch-file line ending.

| Source | Raw-identical files | Newline-only files | Substantive differences |
| --- | ---: | ---: | ---: |
| Folia vs 14b7fee5c866fca9a40ede4a58998fb928140f65 | 50 | 3 | 0 |
| Ruthenium vs aebbbad87498c0718b1287a226789212e6103bcf | 5,081 | 5 | 0 |
| PandaAntiLag vs b74156f661250812ccb108ee6354a2eac3e2c7fa | 27 | 1 | 0 |

The current PandaAntiLag 26.2 revision is b8de06e7e04159b6753c3d94d7d8362a68e80002 and is not represented by the supplied copy.

## Project boundary

Phoenium was created as a separate sibling directory. It contains documentation and repository metadata only. It does not contain a copied source tree, a modified reference file, or generated files derived from a supplied reference.

