// 验证真实范围、路径隔离和引用提交事务，不调用模型。
package dev.local.icb.contract;

import static org.junit.Assert.*;

import com.google.gson.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 文档 C、Q 和安全矩阵对应的独立协议测试。 */
public class ContractTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private SessionStore store;
    private final SessionStore.Key key = new SessionStore.Key("terminal-a", "session-a");

    @Before
    public void setup() {
        store = new SessionStore("project-a");
        store.register(key.terminalId(), key.sessionId(), "/project");
    }

    @After
    public void cleanup() {
        store.clear();
    }

    @Test
    public void partialSelectionPreservesExactCharacters() {
        assertEquals(
                "中文",
                Attachment.snapshot("root", "a", "SELECTION_SNAPSHOT", "a中文b", 1, 3, 1, true)
                        .content());
    }

    @Test
    public void selectionEndingAtNextLineStartDoesNotIncludeNextLine() {
        var item =
                Attachment.snapshot(
                        "root", "a", "SELECTION_SNAPSHOT", "first\nsecond", 0, 6, 1, false);
        assertEquals(Integer.valueOf(1), item.endLineInclusiveOneBased());
    }

    @Test
    public void emptyFileHasSafeRange() {
        var item = Attachment.snapshot("root", "a", "FILE_SNAPSHOT", "", 0, 0, 1, false);
        assertEquals(Integer.valueOf(1), item.endLineInclusiveOneBased());
    }

    @Test
    public void supplementaryCharactersUseUtf16Offsets() {
        var item =
                Attachment.snapshot(
                        "root", "a", "SELECTION_SNAPSHOT", "中\uD83D\uDE00文", 1, 3, 1, true);
        assertEquals("\uD83D\uDE00", item.content());
    }

    @Test
    public void splitSurrogateIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Attachment.snapshot(
                                "root", "a", "SELECTION_SNAPSHOT", "\uD83D\uDE00", 0, 1, 1, false));
    }

    @Test
    public void rawCrLfCannotUseDocumentOffsets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Attachment.snapshot("root", "a", "FILE_SNAPSHOT", "a\r\nb", 0, 4, 1, false));
    }

    @Test
    public void largeSnapshotIsRejectedByBytes() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Attachment.snapshot(
                                "root",
                                "a",
                                "FILE_SNAPSHOT",
                                "中".repeat(50000),
                                0,
                                50000,
                                1,
                                false));
    }

    @Test
    public void pathOnlyHasNoFakeCoordinates() {
        assertNull(Attachment.path("root", "a").startOffset());
        assertNull(Attachment.path("root", "a").content());
    }

    @Test
    public void duplicateJsonKeysAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.parse("{\"session_id\":\"a\",\"session_id\":\"b\"}"));
    }

    @Test
    public void trailingJsonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} {}"));
    }

    @Test
    public void looseJsonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{value:'a'}"));
    }

    @Test
    public void unknownOptionalFieldsRemainCompatible() {
        assertEquals(
                "a",
                Json.required(Json.parse("{\"session_id\":\"a\",\"future\":true}"), "session_id"));
    }

    @Test
    public void missingRequiredFieldIsRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> Json.required(new JsonObject(), "turn_id"));
    }

    @Test
    public void symlinkOutsideAuthorizedRootIsRejected() throws Exception {
        Path root = temporary.newFolder("root").toPath(),
                outside = temporary.newFile("secret.txt").toPath();
        Files.createSymbolicLink(root.resolve("link"), outside);
        PathPolicy policy = new PathPolicy(Map.of("r", root.toRealPath()));
        assertThrows(java.io.IOException.class, () -> policy.resolve("r", "link"));
    }

    @Test
    public void rootPrefixCollisionIsRejected() throws Exception {
        Path root = temporary.newFolder("app").toPath(),
                other = temporary.newFolder("app2").toPath();
        Files.writeString(other.resolve("a"), "a");
        PathPolicy policy = new PathPolicy(Map.of("r", root.toRealPath()));
        assertThrows(java.io.IOException.class, () -> policy.identify(other.resolve("a")));
    }

    @Test
    public void parentTraversalIsRejected() throws Exception {
        Path root = temporary.newFolder("root").toPath();
        assertThrows(
                java.io.IOException.class,
                () -> new PathPolicy(Map.of("r", root)).resolve("r", "../secret"));
    }

    @Test
    public void sensitiveFileIsRejected() throws Exception {
        Path root = temporary.newFolder("root").toPath();
        Files.writeString(root.resolve(".env.local"), "fixture");
        assertThrows(
                java.io.IOException.class,
                () -> new PathPolicy(Map.of("r", root)).resolve("r", ".env.local"));
    }

    @Test
    public void replacingSymlinkIsRevalidated() throws Exception {
        Path root = temporary.newFolder("root").toPath();
        Files.writeString(root.resolve("a"), "a");
        Path outside = temporary.newFile("other").toPath();
        Files.createSymbolicLink(root.resolve("link"), root.resolve("a"));
        PathPolicy policy = new PathPolicy(Map.of("r", root.toRealPath()));
        policy.resolve("r", "link");
        Files.delete(root.resolve("link"));
        Files.createSymbolicLink(root.resolve("link"), outside);
        assertThrows(java.io.IOException.class, () -> policy.resolve("r", "link"));
    }

    @Test
    public void secureDirectoryReadReturnsOnlyAuthorizedFile() throws Exception {
        Path root = temporary.newFolder("secure").toPath().toRealPath();
        Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("src/a.txt"), "fixture 中文");
        assertEquals(
                "fixture 中文", new PathPolicy(Map.of("r", root)).readText("r", "src/a.txt", 131072));
    }

    @Test
    public void secureDirectoryReadRejectsBinaryAndOversize() throws Exception {
        Path root = temporary.newFolder("secure").toPath().toRealPath();
        Files.write(root.resolve("binary"), new byte[] {0, 1, 2});
        Files.writeString(root.resolve("large"), "x".repeat(129));
        PathPolicy policy = new PathPolicy(Map.of("r", root));
        assertThrows(java.io.IOException.class, () -> policy.readText("r", "binary", 131072));
        assertThrows(java.io.IOException.class, () -> policy.readText("r", "large", 128));
    }

    @Test
    public void identicalAttachmentIsIdempotent() {
        var item = Attachment.path("r", "a");
        store.add(key, List.of(item, item));
        assertEquals(1, store.require(key).queued.size());
    }

    @Test
    public void samePathDifferentSnapshotsRemainDistinct() {
        store.add(key, List.of(snapshot("a"), snapshot("b")));
        assertEquals(2, store.require(key).queued.size());
    }

    @Test
    public void queueCountIsBounded() {
        for (int i = 0; i < 16; i++) store.add(key, List.of(Attachment.path("r", "a" + i)));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.add(key, List.of(Attachment.path("r", "overflow"))));
        assertEquals(16, store.require(key).queued.size());
    }

    @Test
    public void retryHasExactlySameBatchAndAutomaticContext() {
        store.add(key, List.of(snapshot("unsaved-marker")));
        var first = store.submit(key, "turn", "semantic", Json.object("cursor", 1));
        var retry = store.submit(key, "turn", "semantic", Json.object("cursor", 99));
        assertSame(first, retry);
        assertTrue(first.output.contains("unsaved-marker"));
    }

    @Test
    public void semanticConflictDoesNotConsumeNextQueue() {
        store.submit(key, "turn", "one", null);
        store.add(key, List.of(snapshot("next")));
        assertThrows(IllegalStateException.class, () -> store.submit(key, "turn", "two", null));
        assertEquals(1, store.require(key).queued.size());
    }

    @Test
    public void newlyAddedReferenceBelongsToNextTurn() {
        store.add(key, List.of(snapshot("first")));
        var first = store.submit(key, "turn1", "a", null);
        store.add(key, List.of(snapshot("next")));
        var second = store.submit(key, "turn2", "b", null);
        assertEquals("first", first.attachments.getFirst().content());
        assertEquals("next", second.attachments.getFirst().content());
    }

    @Test
    public void missingTurnCannotConsumeReferences() {
        store.add(key, List.of(snapshot("first")));
        assertThrows(IllegalArgumentException.class, () -> store.submit(key, "", "a", null));
        assertEquals(1, store.require(key).queued.size());
    }

    @Test
    public void explicitBudgetFailurePreservesQueue() {
        store.add(key, List.of(snapshot("a".repeat(25000))));
        assertThrows(IllegalArgumentException.class, () -> store.submit(key, "turn", "a", null));
        assertEquals(1, store.require(key).queued.size());
    }

    @Test
    public void automaticOversizeDropsOnlyAutomaticContent() {
        var batch =
                store.submit(
                        key,
                        "turn",
                        "a",
                        Json.object("relativePath", "a", "content", "x".repeat(30000)));
        assertFalse(batch.output.contains("x".repeat(100)));
        assertTrue(batch.output.contains("relativePath"));
    }

    @Test
    public void automaticSelectionIdenticalToExplicitSnapshotIsNotRepeated() {
        store.add(key, List.of(snapshot("abc")));
        var active =
                Json.object(
                        "rootId",
                        "r",
                        "relativePath",
                        "a",
                        "content",
                        List.of(Json.object("start", 0, "end", 3, "content", "abc")));
        var batch = store.submit(key, "turn", "a", active);
        String context =
                Json.parse(batch.output)
                        .getAsJsonObject("hookSpecificOutput")
                        .get("additionalContext")
                        .getAsString();
        JsonObject payload = Json.parse(context.substring(context.indexOf("\n\n") + 2));
        assertFalse(payload.getAsJsonObject("activeEditor").has("content"));
        assertEquals(
                "project-a",
                payload.getAsJsonArray("attachments")
                        .get(0)
                        .getAsJsonObject()
                        .get("projectId")
                        .getAsString());
    }

    @Test
    public void explicitResumeOwnerSurvivesRepeatedRegistration() {
        var other = new SessionStore.Key("terminal-b", key.sessionId());
        store.register(other.terminalId(), other.sessionId(), "/project");
        store.selectOwner(key);
        store.register(key.terminalId(), key.sessionId(), "/project");
        store.register(other.terminalId(), other.sessionId(), "/project");
        assertFalse(store.require(key).conflict);
        assertTrue(store.require(other).conflict);
        store.add(key, List.of(snapshot("selected-owner")));
        assertTrue(store.submit(key, "owner-turn", "hash", null).output.contains("selected-owner"));
        assertThrows(
                IllegalStateException.class, () -> store.submit(other, "other-turn", "hash", null));
    }

    @Test
    public void emptySubmissionDoesNotInjectEmptyContext() {
        var batch = store.submit(key, "empty-turn", "hash", null);
        assertEquals("{}", batch.output);
        assertTrue(batch.attachments.isEmpty());
    }

    @Test
    public void twoTerminalsResumingSameSessionAreConflict() {
        store.register("terminal-b", "session-a", "/project");
        assertTrue(store.require(key).conflict);
        assertThrows(IllegalStateException.class, () -> store.add(key, List.of(snapshot("a"))));
        store.close(new SessionStore.Key("terminal-b", "session-a"));
        assertFalse(store.require(key).conflict);
    }

    @Test
    public void differentSessionsCannotConsumeEachOthersReferences() {
        var other = new SessionStore.Key("terminal-b", "session-b");
        store.register(other.terminalId(), other.sessionId(), "/project");
        store.add(key, List.of(snapshot("private-a")));
        assertFalse(store.submit(other, "turn", "b", null).output.contains("private-a"));
        assertEquals(1, store.require(key).queued.size());
    }

    @Test
    public void acknowledgementOnlyChangesDeliveryState() {
        var batch = store.submit(key, "turn", "a", null);
        store.ack(key, batch.id);
        assertEquals("HANDED_TO_CLI", batch.state);
    }

    @Test
    public void interruptedBatchIsNotAutomaticallyRequeued() {
        store.add(key, List.of(snapshot("a")));
        var batch = store.submit(key, "turn", "a", null);
        store.finish(key, "turn", true);
        assertEquals("INTERRUPTED", batch.state);
        assertTrue(store.require(key).queued.isEmpty());
    }

    @Test
    public void staleQueuedReferenceRequiresConfirmation() {
        var item = snapshot("old");
        var stale =
                new Attachment(
                        item.attachmentId(),
                        item.rootId(),
                        item.relativePath(),
                        item.kind(),
                        item.startOffset(),
                        item.endOffsetExclusive(),
                        item.startLineOneBased(),
                        item.endLineInclusiveOneBased(),
                        item.documentModificationStamp(),
                        item.contentSha256(),
                        false,
                        Instant.now().minusSeconds(1801),
                        item.content());
        store.add(key, List.of(stale));
        assertThrows(IllegalStateException.class, () -> store.submit(key, "turn", "a", null));
    }

    @Test
    public void simultaneousRetryReservesOnlyOnce() throws Exception {
        store.add(key, List.of(snapshot("a")));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<SessionStore.Batch>>();
            for (int i = 0; i < 20; i++)
                futures.add(executor.submit(() -> store.submit(key, "turn", "a", null)));
            var batch = futures.getFirst().get();
            for (var future : futures) assertSame(batch, future.get());
        }
        assertEquals(1, store.require(key).turns.size());
    }

    @Test
    public void closingSessionRejectsAnAlreadyWaitingAdd() throws Exception {
        var session = store.require(key);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker;
        synchronized (session) {
            worker =
                    new Thread(
                            () -> {
                                try {
                                    store.add(key, List.of(snapshot("must-not-leak")));
                                } catch (Throwable ex) {
                                    failure.set(ex);
                                }
                            });
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (worker.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
                Thread.sleep(1);
            assertEquals(Thread.State.BLOCKED, worker.getState());
            store.close(key);
        }
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException);
        assertTrue(session.queued.isEmpty());
    }

    /**
     * 生成未保存文档测试快照。
     *
     * @param text 当前测试用例需要冻结的正文
     */
    private static Attachment snapshot(String text) {
        return Attachment.snapshot("r", "a", "SELECTION_SNAPSHOT", text, 0, text.length(), 1, true);
    }
}
