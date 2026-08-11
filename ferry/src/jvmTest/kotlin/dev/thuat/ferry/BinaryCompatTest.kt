package dev.thuat.ferry

import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertNotNull
import okio.Path

class BinaryCompatTest {

    /**
     * The published dev.thuat:ferry-work:0.2.0 was compiled against this exact full-arity
     * descriptor (RepoDownloadWorker calls `download(repoId, path) { ... }`, which supplies all
     * three parameters and links the non-default method directly). The fileFilter feature was
     * added as a separate overload precisely so this descriptor survives verbatim: deleting or
     * reshaping the 3-argument download is a runtime NoSuchMethodError for every consumer
     * resolving ferry-work 0.2.0 against a newer :ferry, even though all sources still compile.
     *
     * The method name below is not literally `download`: Kotlin mangles any function whose
     * return type is `kotlin.Result<T>` by appending a hash suffix computed from its signature
     * (`-BWLJW6A` here), so a Kotlin caller's source-level `ferry.download(repoId, path) { ... }`
     * actually resolves and links against `download-BWLJW6A` at the bytecode level. Confirmed
     * against the real published artifact — `dev.thuat:ferry-jvm:0.2.0`'s jar
     * (`~/.m2/repository/dev/thuat/ferry-jvm/0.2.0/ferry-jvm-0.2.0.jar`) contains this exact
     * method name and descriptor, so this is the actual load-bearing symbol, not the unmangled
     * `download`.
     */
    @Test
    fun `the 3-argument download descriptor ferry-work 0-2-0 links against still exists`() {
        val method = RepoDownloader::class.java.getDeclaredMethod(
            "download-BWLJW6A",
            String::class.java,
            Path::class.java,
            Function1::class.java,
            Continuation::class.java,
        )
        assertNotNull(method)
    }
}
