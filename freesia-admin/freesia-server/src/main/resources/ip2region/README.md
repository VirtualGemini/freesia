# Historical offline IP location databases

The two xdb files are reused byte-for-byte from the previous product, as explicitly
requested by the maintainer on November 21, 2026. They are auxiliary audit data,
not proof of a user's physical location or an authorization input.

Source: historical revision `9dd4f30af568c3307621c0fe0f63fe2b355308d4`,
`backend/velox-system/src/main/resources/ip2region/`. Their Git blobs match
ip2region upstream revision `072b6fed24688490e76242caac96e3c795fdb920`.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `ip2region_v4.xdb` | 10641955 | `cabf0493883e94015da95ce9ef22b514d9494eb47f66b22942c7710c5d6720be` |
| `ip2region_v6.xdb` | 36086712 | `5859527ac7a44b9207d08e303b3d10634d01d0a5ced85dc3f17928c40829877c` |

The upstream license is retained in `META-INF/licenses/ip2region-LICENSE.txt`.
The library's license and the database's provenance are separate matters.
Tracing these bytes to upstream does not independently establish every data
contributor's rights or guarantee present accuracy. Dependency/data decisions
are recorded in repository issue #9. No raw generation sources, old accounts,
credentials or production configurations are imported.

Default classpath data can be replaced by a local `file:` resource in YAML.
Use trusted read-only files; replace by a new path and restart rather than
overwriting files while requests are reading them. Lookups never download data.
