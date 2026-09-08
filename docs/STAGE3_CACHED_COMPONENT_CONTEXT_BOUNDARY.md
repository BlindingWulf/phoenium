# Stage 3 cached component context boundary

Stage 3 does not admit interpreted block NBT paths whose leading fields are
`components.minecraft:custom_name`. `FullNbtPathInputs` classifies that finite
path from syntax alone, and `FullSavedContextInputs` reports it as unresolved
before the original FULL body runs. Audited direct, uncached entity and block
entity text fields remain supported.

The pinned 26.2 sources establish why a retained component graph or an
instantaneous cache lookup is insufficient:

* `DataComponents.java:108` creates one static `EncoderCache`, and lines 123-124
  apply it to `CUSTOM_NAME`.
* `EncoderCache.java:36` executes `LoadingCache.getUnchecked`. A concurrent load
  of an equal key can therefore make the original caller wait.
* Original vanilla serialization can reach that shared cache through independent
  paths, including `BlockDataSource.java:40`, `ContextNbtProvider.java:51`,
  `BlockPredicate.java:84`, `ServerGamePacketListenerImpl.java:1044`, and
  `BlockInput.java:85,89`.

A regional FULL receipt cannot prevent one of those independent producers from
starting a load after final validation and before the original serializer reaches
`getUnchecked`. Admitting the path would permit waiting while holding regional
ownership. Phoenium therefore neither pre-encodes the component nor intercepts
the process-wide cache. Supporting this path later requires a reviewed producer
fence that covers every legitimate encoder owner while preserving original
serialization and the no-owner-wait rule.

The same explicit boundary applies to other cached `DataComponents`, unknown or
unindexed component fields, and mod-defined serializers whose dependency and
producer footprints have not been proven finite.
