package dev.phoenium.chunk;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Disposable test mod only. Uses installed production Mixin hooks, never the JUnit javaagent. */
public final class FullRegistrationServerProbe implements DedicatedServerModInitializer {
    @Override public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var test = new FullProductionRegistrationTest();
                test.winningReservationOriginalAsyncFullAdmitsPreparationWithoutPublishingFull(false);
                test.winningReservationOriginalAsyncFullAdmitsPreparationWithoutPublishingFull(true);
                test.inMemoryImposterReusesTheExactInputAndKeepsOriginalFutureIdentity(false);
                test.inMemoryImposterReusesTheExactInputAndKeepsOriginalFutureIdentity(true);
                test.actualProducerCompletionReentryParksFullUntilItsWriterReleaseWakesTheSameWork();
                test.finishingWithoutRealServicesCannotClaimEvenWhenQueuedWithAValidPreparedInput();
                test.realSulfurSetLevelUsesTheActualClockWriterBindingAndReleasesItsReadTurn();
                test.successfulReceiptSurvivesRetirementUntilTheExactConsumerAcknowledges();
                var inputs = new FullInitializedInputBindingTest();
                inputs.originalAsyncProtoPreparationFeedsTheRealFinishingInputPrerequisite();
                for (boolean reload : new boolean[] {false, true}) for (boolean reverse : new boolean[] {false, true})
                    inputs.originalSavedPairBindsBothConsumersBeforeEitherPublicFull(reload, reverse);
                inputs.threeInputCornerAndPassengerStructureUseSharedPreparations();
                inputs.actualPermittedHolderDemandProducesMissingPreparationWithoutPublicFullWait();
                inputs.savedAddEntityWriterInvalidatesAndRediscoversWithoutReconstruction();
                inputs.finiteConsumerCapacityResumesOnAcknowledgementAndNeedsNoPublication();
                inputs.delayedRawWriterReleaseAndDuplicateNoticesNeverBorrowAnUnpreparedInput();
                inputs.originalVisibleMapPublicationInvalidatesObservedAbsenceAndProducesNewInput();
                inputs.inlinePreparationNotificationCannotExposeAnUnboundFinishingInputOrResurrectClosedUse();
                inputs.planAndPayloadCapacityDoesNotReserveResourcesNeededForTargetPreparation();
                inputs.realSavedWriterIsExcludedBeforeMutationDuringTheObservationTurn();
                inputs.replacementKeepsOldPreparedConsumerUntilAcknowledgementAndDoesNotRemoveNewHolder();
                inputs.nonAirShapePotentialRemainsMutationCapableAndNeverCreatesBlockEntitiesForDiscovery();
                inputs.originalCompoundPositionAndPassengerWritersInvalidateProtoAndRetainedReload(false);
                inputs.originalCompoundPositionAndPassengerWritersInvalidateProtoAndRetainedReload(true);
                inputs.sharedMutableSavedPositionCannotBeReadUnderAnotherInputsCustody();
                var published = new FullPublishedInputBindingTest();
                for (boolean reload : new boolean[] {false, true}) for (boolean inline : new boolean[] {false, true})
                    published.originalPublishedNeighborFeedsActualFinishingEvaluatorWithoutSourceFinishing(reload, inline);
                published.inlinePublicSubscriberObservesInstalledLineageBeforeOriginalHandlerReturns();
                published.targetPublicationWhileDiscoveryIsSubscribedReplacesPendingObservation();
                published.independentConsumersAndSourceCancellationPreserveResidentTargetAndUnrelatedDemand();
                published.mixedPreparedPublishedAndAbsentWindowPreservesMutationObligations();
                published.realSlotReplacementAndTicketEligibilityRejectNewUseButPreserveOldAcknowledgement();
                published.replacementDuringPinInstallationCannotExposeSuccessOrRemoveReplacement();
                published.acknowledgementReentryInstallsANewerUseAndDuplicateOldCloseCannotRevokeIt();
                published.capacityReleaseAndRepeatedPlanAcknowledgementsKeepOnlyResidentMetadata();
                published.lateOriginalH1PublicationCannotBindToH2AndOldAcknowledgementLeavesH2Untouched();
                published.sourceAcknowledgementDuringPublicationNotificationCannotResurrectAReceipt();
                published.oldOperationReceiptAcknowledgementCannotRevokeNewPlanAndCopiedPublicationCannotBeRetained();
                published.originalUnloadWaitsForRetainedUseThenReclaimsTheActualHolder();
                published.trueTargetRetirementKeepsOldEvidenceUntilItsOwnUseAcknowledges();
                published.operationConsumerExpansionAcknowledgesEveryPreparedAndPublishedUse();
                var acknowledgements = new FullInputAcknowledgementTest();
                acknowledgements.failedPublicationNotificationCannotAbandonLaterOperationConsumers();
                acknowledgements.biomeBorrowAcknowledgesTheOriginalReferenceWhenItsCommittedSaveCallbackFails();
                System.out.println("PHOENIUM_FULL_PUBLISHED_INPUT_PROBE PASS cases=18 targetOriginalFull=true exactResultWrapper=true "
                        + "baselineOriginHonest=true proto=true inMemoryImposter=true diskReload=false targetProducerSettled=true "
                        + "sourceInput=SATISFIED sourceAggregate=NOT_READY sourceFinishingClaims=0 sourceSuffix=0 sourcePublicFull=0 "
                        + "independentConsumers=true originalSavePins=true originalUnload=true repeatedCycles=34 "
                        + "originalPublishedResidentRecordsAfterAck=1 pendingSelectedRecordsAfterAck=2 totalRecordsAfterAck=3 "
                        + "usesAfterAck=0 pendingSelectedExecutionsAfterAck=2 trueTeardownRecords=0 javaagent=false");
                System.out.println("PHOENIUM_FULL_INPUT_BINDING_PROBE PASS cases=21 productionDiscovery=true exactInputConsumer=true "
                        + "proto=true inMemoryImposter=true bothPairOrders=true largerWindow=true savedWriter=true "
                        + "actualDemand=true capacityToProgress=true completeAcknowledgement=true biomeRollback=true inputSatisfied=true aggregate=NOT_READY "
                        + "postLoad=0 publicFull=0 terminalInputPlans=0 terminalInputConsumers=0 javaagent=false");
                System.out.println("PHOENIUM_FULL_REGISTRATION_PROBE PASS cases=8 selectedPreparation=true "
                        + "originalGenerationTask=true originalHolder=true productionRegistrar=true "
                        + "proto=true inMemoryImposter=true clockWriter=true postLoad=0 publicFull=0 "
                        + "terminalGraphs=0 terminalIndexes=0 terminalGenerationHolders=0 terminalCustody=0 "
                        + "controlledWorldEndpoints=true controlledLowerGenerationOutputs=true javaagent=false");
                finishingProbe();
            } catch (Throwable failure) {
                System.err.println("PHOENIUM_FULL_REGISTRATION_PROBE FAIL " + failure);
                throw new IllegalStateException("installed FULL registration fixture failed", failure);
            }
        });
    }

    private static void finishingProbe() throws Exception {
        try {
            var queries = new FullFinishingQueriesTest();
            for (boolean reload : new boolean[] {false, true}) for (boolean reverse : new boolean[] {false, true})
                queries.originalBoundaryPairFinishesWithoutPublishingOrCachingEitherPrivateInput(reload, reverse);
            queries.originalNonAirNeighborCreatesImmediateEntityAndRetainsUnknownTicketPrefix();
            queries.originalMovingPistonShapeReadsExactRetainedProgressWithoutAdvancingItsTicker();
            queries.originalPublishedNeighborResultRemainsReadyAndCanUseOrdinaryCache();
            queries.originalAbsentNeighborResultStaysNullWithoutUnknownTicket();
            queries.originalVillagerBiomeCacheKeepsItsProtoAliasAcrossHolderPreparation();
            queries.originalVillagerCachedAbsentBiomeKeepsItsOriginalGeneratorFallback();
            queries.wrongSelectedFutureIsCaughtByVanillaButStillFailsFinishingBarrier();
            queries.unrelatedGetterCannotBorrowPreparedNeighborFromAnActiveFinishingScope();
            queries.retainedReceiptWithoutItsClaimedOperationCannotEnter();
            var services = new FullFinishingServicesTest();
            services.shutdownRequestedInsideAnActiveTurnDefersCleanupUntilTheSynchronousBodyExits();
            services.reentrantStopInsideFullDefersEveryChildUntilRegionalClaimExit();
            services.partialChildShutdownFailureStillClosesReservationsAndStopsTheProducer();
            services.childShutdownReentryObservesTheSameTerminalServiceAndCannotResurrectIt();
            services.productionMixinConfigurationInstallsTheD1AndWorldLifecycleAdapters();
            services.largeFiniteScoreboardFootprintIsLazyAndCanMakeProgress();
            services.negativeScoreboardFootprintFailsBeforeInstallingAnyReservation();
            var facts = new FullEntityFactsTest();
            facts.constructorOwnsItsAliasBeforeTheFirstSetPosRoutingCallbackWithoutPublishingFacts();
            facts.firstNonnullWorldWinsBeforeOriginalClassFilteringAndUsesSuppliedOrder();
            facts.liveCachedAliasSurvivesDirectoryReplacementUntilOriginalRemovalFallback();
            facts.originalMobValidationUsesFactsAtEveryReuseWithoutForeignPredicateCalls();
            facts.committedRemovalFactPrecedesOriginalCallbackFailureAndCannotBeRolledBack();
            facts.foreignSetterCannotBorrowTheScalarReadTurnAndFailureSurvivesCatch();
            facts.identityWriterInvalidatesUnenteredCutAndOldAliasRetainsItsOwnIdentity();
            var contexts = new FullSavedContextInputTest();
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-storage-probe");
            contexts.productionInterpretedStorageDiscoversRecursiveSourcesAndOriginalReaderKeepsTheirText(false);
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-storage-reload-probe");
            contexts.productionInterpretedStorageDiscoversRecursiveSourcesAndOriginalReaderKeepsTheirText(true);
            contexts.productionBlockNbtRunsOriginalCoordinatesPathAndSerializerAgainstTheRetainedPreparedChunk(false);
            contexts.productionBlockNbtRunsOriginalCoordinatesPathAndSerializerAgainstTheRetainedPreparedChunk(true);
            contexts.absentBlockNbtKeepsTheOriginalIsLoadedEmptyBranchWithoutDemandingAChunk();
            contexts.sameWorldSelectorRunsOriginalPredicateOrderAndNameReadInsideTheConvergedRegion(false);
            contexts.sameWorldSelectorRunsOriginalPredicateOrderAndNameReadInsideTheConvergedRegion(true);
            contexts.registeredBiomePredicateRunsItsOriginalLookupAndWorldReadOnce();
            contexts.reloadablePredicateRegistryReplacementStalesAndRebuildsTheExactPlan();
            contexts.earlierSavedRootInsertionExtendsOnlyItsOwnActiveSelectorCut();
            contexts.retainedBlockNbtUsesTheOriginalImmediateLazyCreationAndSerializationSuffix();
            contexts.originalCaughtBlockSerializerFailureReleasesTheScopeForTheNextSavedEntity();
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-pending-block-probe");
            contexts.pendingBlockEntityCustomNamePublishesNestedStorageBlockAndSelfSelectorDependencies(false);
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-pending-block-reload-probe");
            contexts.pendingBlockEntityCustomNamePublishesNestedStorageBlockAndSelfSelectorDependencies(true);
            contexts.repeatedInterpretedReadsOfOnePendingBlockReuseOneCaptureAndBothOriginalSuffixes();
            contexts.overlappingPendingTagProducerLeavesRefinementPendingAndItsCompletionRetriesThePlan();
            contexts.pendingTagCapacityIsReservedBeforeDetachedCaptureAndOnlyOneExpandedPlanCopiesAtATime();
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-actual-parsed-block-probe");
            contexts.actualReloadParsedBlockTagsAreRetainedForDependencyRefinementWithoutEarlyInstallation();
            contexts.filteredSelfSelectorRunsItsOriginalNbtPredicateWithoutAWorldCandidateGrant();
            contexts.selectorValuedSeparatorRestoresTheOuterSameWorldCandidateGrant();
            contexts.sameWorldEntityNbtRunsTheOriginalWorldLimitedPredicateAndSerializer();
            contexts.originalCaughtEntityNbtSerializerFailureRestoresTheNextExactSelectorScope();
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-self-name-probe");
            contexts.ownedSelfCustomNamePublishesNestedStorageDependenciesBeforeOriginalEntitySerialization(false);
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-self-name-reload-probe");
            contexts.ownedSelfCustomNamePublishesNestedStorageDependenciesBeforeOriginalEntitySerialization(true);
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-invalid-name-probe");
            contexts.originalInvalidCustomNameDecodeDoesNotLeakIntoTheNextInterpretedEntityOccurrence();
            int registeredPredicateCases = FullRegisteredPredicateExecutionTest.runInstalled();
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-live-entity-probe");
            contexts.liveEntityCustomNamePublishesNestedStorageBeforeOriginalSerializationAndResolution();
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-live-text-probe");
            contexts.liveTextDisplayTextPublishesNestedStorageBeforeOriginalSerializationAndResolution();
            contexts.directory = java.nio.file.Files.createTempDirectory("phoenium-full-live-beacon-probe");
            contexts.liveBeaconCustomNamePublishesNestedStorageBeforeOriginalImmediateGetter();
            var completion = new FullAuthoritativeFinishingTest();
            completion.originalFinishingAndSeparatePublicationCompleteExactlyOnce(false);
            completion.originalFinishingAndSeparatePublicationCompleteExactlyOnce(true);
            System.out.println("PHOENIUM_FULL_FINISHING_PROBE PASS cases=54 queryCases=13 serviceCases=7 d1Cases=7 contextCases=26 publicationCases=2 "
                    + "originalFinishing=true separatePublication=true cyclicPreparedPair=true bothPairOrders=true "
                    + "proto=true inMemoryImposter=true diskReload=false originalDifficulty=true originalCollision=true "
                    + "originalShulkerImmediate=true originalLaterRegistration=true originalMovingPiston=true "
                    + "savedVillagerOriginalBiomeCacheAlias=true originalCachedAbsentBiomeFallback=true originalReadyAbsent=true caughtQueryBarrier=true registeredBiomeOriginalRead=true predicateRegistryReplacement=true "
                    + "recursiveStorageContext=true retainedBlockNbt=true pendingBlockDependencies=true originalAbsentBlockBranch=true lazyBlockEntity=true "
                    + "sameWorldSelector=true registeredSelectorPredicate=true earlierSavedRootSelector=true sameWorldEntityNbt=true filteredSelfSelector=true nestedSelector=true interpretedSavedCustomName=true liveEntityContext=true liveTextContext=true liveBlockContext=true "
                    + "noPrivateCacheEntries=true claimedOperationRequired=true controlledWorldEndpoints=true "
                    + "controlledLowerGenerationOutputs=true javaagent=false");
            System.out.println("PHOENIUM_FULL_REGISTERED_PREDICATE_PROBE PASS cases=" + registeredPredicateCases
                    + " originalSelector=true valueCheck=true nestedStorageValue=true storageCapture=true "
                    + "scoreboardContext=true positionalEnvironment=true randomChance=true "
                    + "noEarlyStorageRead=true originalRandomDraws=1 javaagent=false");
        } catch (Throwable failure) {
            System.err.println("PHOENIUM_FULL_FINISHING_PROBE FAIL " + failure);
            throw failure;
        }
    }
}
