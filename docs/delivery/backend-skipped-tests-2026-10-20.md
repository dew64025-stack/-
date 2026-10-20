# 本次后端跳过测试逐项清单（2026-10-20）

来自本次 Maven Surefire XML，39 项，均为已有环境开关未启用。包含核心业务与原生并发测试，不能计入已通过。模块含跨模块场景时以测试类为归属依据。

## MysqlCommunityPrivacyTest

所属模块：社区／互助／匿名隐私／跨模块原生并发。共 24 项。

| 测试方法 | 本次跳过原因 |
| --- | --- |
| `cachedReceiptCannotRevealIdentityWhenDedicatedAuditInsertFailsAndRetryIsSafe` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `ownershipNeverCrossesCampusAndCommentsRemainOwnerMaintained` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `nativeConcurrentLastRoleUsesCurrentRowsAfterBothRepeatableReadSnapshots` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `investigationRejectsCrossCampusTargetAndUnrelatedReportBeforeDisclosure` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `nativeConversationReplaySeesCompleteWinnerFromOldSnapshot` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `minimumPrivilegeAppCanMaintainAnonymousOwnershipWithoutMappingSelect` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `unsignedTeamBoundaryBindsActualApplicantAndSupportsLeaveWithMinimumAppGrants` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `nativeBorrowBothOrdersAdvanceFromTwoOldRepeatableReadSnapshots` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `nativeLostDuplicateReturnsCurrentIgnoredStateAfterEarlierSnapshot` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `minimumAppFileMetadataSupportsUploadScanCleanupAndImmutableVersions` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `investigationUsesHeaderDedicatedAuditAndReceiptOnlyReplay` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `investigatorCommentAndCurrentPermissionAreCheckedBeforeEveryDisclosure` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `nativeLostEventsRemainTwoPartyAcrossEarlierRepeatableReadSnapshot` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `nativeLegacyTotalCapacityCannotUseEarlierSnapshot` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `deliveredReminderBatchNeverStarvesLaterReminders` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `investigationConfigurationKeepsPrimaryBusinessTransactions` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `nativeReadCursorCountsMessagesCommittedAfterItsOldSnapshot` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `documentedAppCanReadPrivacyBusinessTables` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `concurrentSameKeyInvestigationsKeepOneReceiptAndAuditEveryDisclosure` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `nativeMessageRetrySeesCommittedWinnerFromOldSnapshot` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `minimumAppBorrowRequiresBothPeopleForHandoverAndReturn` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `nativeGovernanceKeepsCampusAuditAndUnsignedIdentifiers` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `minimumAppCannotMutateVersionsAuditEventsOrReadConverterState` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `nativeDifferentKeyFirstChecklistCreationReturnsOneCompleteWinnerAcrossOldSnapshots` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |

## OllamaLiveClientTest

所属模块：本地真实模型。共 1 项。

| 测试方法 | 本次跳过原因 |
| --- | --- |
| `localQwen3ReturnsJsonCompletion` | 未设置环境变量 `OLLAMA_LIVE_TEST` |

## LiveFileIntegrationTest

所属模块：真实文件全链路。共 1 项。

| 测试方法 | 本次跳过原因 |
| --- | --- |
| `realPrivateUploadScanSignatureAndCleanupAndEicarDetection` | 未设置环境变量 `CZT_LIVE_FILES_TEST` |

## MysqlFileRegressionTest

所属模块：文件 MySQL 原生驱动／并发。共 2 项。

| 测试方法 | 本次跳过原因 |
| --- | --- |
| `connectorDatetimeCompletesUploadWithoutGlobalDriverWorkaround` | 未设置环境变量 `CZT_MYSQL_FILES_TEST` |
| `rrDeleteSeesReferenceCommittedAfterItsSnapshotBeforeAttachmentLock` | 未设置环境变量 `CZT_MYSQL_FILES_TEST` |

## MysqlDMigrationTest

所属模块：消息历史迁移。共 2 项。

| 测试方法 | 本次跳过原因 |
| --- | --- |
| `upgradePreservesLegitimatePrivateHistoryAndSeparateBuyerContexts` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |
| `upgradeQuarantinesMixedPrivateChatWithoutDiscardingHistory` | 未设置环境变量 `CZT_ABC_C_MYSQL_TEST` |

## MysqlServiceRegressionTest

所属模块：预约／服务／原生并发。共 9 项。

| 测试方法 | 本次跳过原因 |
| --- | --- |
| `unsignedMapsReadOwnerReservationWithRealConnector` | 未设置环境变量 `CZT_ABC_B_MYSQL_TEST` |
| `approvalCannotUseAvailableSlotFromEarlierRepeatableReadSnapshot` | 未设置环境变量 `CZT_ABC_B_MYSQL_TEST` |
| `legacyNullSlotCapacityPreservesOccupiedExclusiveMode` | 未设置环境变量 `CZT_ABC_B_MYSQL_TEST` |
| `moderationCommittedAfterSnapshotStillBlocksPrivateIssueInteraction` | 未设置环境变量 `CZT_ABC_B_MYSQL_TEST` |
| `legacyOverlappingExclusiveAvailabilityMatchesWriteConflict` | 未设置环境变量 `CZT_ABC_B_MYSQL_TEST` |
| `approvalCannotUseActiveResourceFromEarlierRepeatableReadSnapshot` | 未设置环境变量 `CZT_ABC_B_MYSQL_TEST` |
| `realMysqlFinalSeatIsUniqueAndWorkerReleasesExpiredHold` | 未设置环境变量 `CZT_ABC_B_MYSQL_TEST` |
| `concurrentVotesCountBothAfterWaitingForIssueLock` | 未设置环境变量 `CZT_ABC_B_MYSQL_TEST` |
| `mixedLegacyModesBlockBookingWithExplicitConfigurationError` | 未设置环境变量 `CZT_ABC_B_MYSQL_TEST` |
