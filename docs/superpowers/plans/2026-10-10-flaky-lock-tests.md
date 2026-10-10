# Flaky lock tests under load

Race tests already wait for the blocked backend with PgLocks; what failed was the fixed 10-15 s
hang guards on latches and Future.get (the holder's own release latch expired while the
lock-wait poll ran, and the post-release HTTP work exceeded 10 s on a loaded machine).

Change: raise those guards to 60 s (TierInventoryRacePostgresTest @Timeout to 300 s) in
AudiencePlanListIntegrationTest, AudiencePlanControllerIntegrationTest,
EventSoftDeleteRaceIntegrationTest, TierInventoryRacePostgresTest. Assertions unchanged.

Verify: both audience-plan classes 3x, EventSoftDelete/TierInventory once, SpringContextGuardTest;
red proof of the two named tests by removing the lock in a scratch copy.
