# Disposition of the 48 FullNonselfInputDecisionTest failures

These were legitimate fixture failures, not obsolete behavior assertions. All 48
behavioral cases remain enabled. No production FULL contract or exclusion changed.

The independently accepted prototype is commit
`542b352eff9fbc8878d00cb07782b45460565f66`. Its committed
`STAGE3_FULL_NONSELF_INPUT_DECISION.md` identifies the original mapped Minecraft
26.2 `ChunkStatusTasks.class` SHA-256 as
`AB9A75EC31F57DF5502EE65FFE413D8099097417C2ED1C928DA3890B523E3B05`.
The current original mapped game JAR contains those exact bytes. The accepted
fixture also checks the supplier descriptor, complete native method-call inventory,
the runPostLoad cut/local index, branch containment and original exception handlers.
Those checks still run before either copied fragment can execute.

Commit `480fdc4cf16a00d65960b343f81ed15904d06dd9` changed only the class digest in
`FullConstructionFragments` to a different value beginning `4e5c`. The retained
original game input and accepted prototype authority do not match that new digest.
This continuation restores the independently documented AB9A digest; it does not
derive a replacement expectation merely from whichever bytes happen to run.

The preserved restored baseline has three visible failure groups:

| Cases | Visible failure | Actual earlier cause and disposition |
| --- | --- | --- |
| 45 | Expected successful fixture outcome, observed false | Fragment class initialization rejects the wrong raw-class pin before construction; restore the accepted fixture input contract. |
| 2 | Expected one native construction/replacement, observed zero | Same pre-construction rejection prevents the expected side effect; keep the side-effect assertion. |
| 1 | ProtoChunk cannot cast to ImposterProtoChunk | Construction never reached the native holder replacement; keep the required replacement and cast semantics. |

`nonself-contract-diagnostic` preserves the exposed original pin error and first
construction outcome. A diagnostic assertion message was added so this cause will
be visible instead of collapsing into only `expected true`. The unchanged
behavioral matrix then passed all 48 cases in `nonself-native-pin-restored`.
Final unfiltered verification must rerun them with the complete candidate suite;
the campaign regression report records that actual result and exact XML.

No cases are suppressed or reclassified as passing without execution. There is no
unresolved contract debt from these 48 once final verification passes. The earlier
1,225-test historical discrepancy remains unverified historical accounting: the
missing original selection/receipts were not found and no broad search was reopened.
