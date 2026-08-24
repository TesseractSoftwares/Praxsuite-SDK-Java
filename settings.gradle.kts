rootProject.name = "praxsuite-sdk"

// The Kotlin extensions live here rather than in their own repository, for one practical reason:
// they depend on the Java artifact, and that artifact is not on Maven Central yet. A separate repo
// would have to consume a published version that does not exist. As a module it can depend on the
// project directly, build and test in the same run, and still publish as its own artifact.
//
// This is the one place the portfolio's one-repo-per-SDK pattern is broken, and it is broken on
// purpose: Kotlin is not a separate SDK, it is a thin idiomatic face over this one.
include("kotlin")
project(":kotlin").name = "praxsuite-sdk-kotlin"
