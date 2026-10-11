import {after, before, test} from "node:test";
import assert from "node:assert/strict";
import {createServer} from "vite";
import {createSSRApp, nextTick, reactive, watch} from "vue";
import {renderToString} from "vue/server-renderer";
import {marked} from "marked";

let vite;
let ChatApp;

before(async () => {
    vite = await createServer({
        server: {middlewareMode: true, hmr: false, watch: null},
        ssr: {external: ["vue", "vue/server-renderer", "dompurify", "marked"]}
    });
    ChatApp = (await vite.ssrLoadModule("/src/ChatApp.vue")).default;
});
after(async () => { await vite?.close(); });

function storage(t) {
    const original = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
    Object.defineProperty(globalThis, "localStorage", {
        configurable: true, value: {getItem: () => null, setItem() {}, removeItem() {}}
    });
    t.after(() => original ? Object.defineProperty(globalThis, "localStorage", original)
        : Reflect.deleteProperty(globalThis, "localStorage"));
}

async function render(data) {
    const state = Object.fromEntries(Object.keys(ChatApp.data()).map(key => [key, data[key]]));
    return renderToString(createSSRApp({...ChatApp, data: () => state,
        methods: {...ChatApp.methods, renderMarkdown: text => marked.parse(text)}}));
}

function createVm() {
    const vm = reactive({...ChatApp.data(), $refs: {summaryPicker: {showModal() {}, close() {}}}, $nextTick: nextTick});
    for (const [name, method] of Object.entries(ChatApp.methods)) vm[name] = method.bind(vm);
    return vm;
}

const summaryDocument = {id: 38, name: "数据库.pdf", status: "READY", summaryStatus: "READY"};

test("puts summary selection at the bottom left and never opens a local file input", async t => {
    storage(t);
    const vm = createVm();
    const html = await render(vm);
    assert.match(html, /class="composer-footer"><button class="summary-select-button"/);
    assert.doesNotMatch(html, /<select|class="summary-picker"/);
    assert.doesNotMatch(html, /type="file"/);
    assert.match(html, /选择已上传的资料/);
    assert.match(await render({...vm, sending: true}), /summary-select-button" type="button" disabled/);
});

test("loads existing documents, shows their status and selects one without uploading or sending chat", async t => {
    storage(t);
    let finish;
    t.mock.method(globalThis, "fetch", async (url, options) => {
        assert.equal(url, "/api/documents");
        assert.equal(options, undefined);
        return new Promise(resolve => { finish = resolve; });
    });
    const vm = createVm();
    let opened = false;
    let closed = false;
    vm.$refs.summaryPicker = {showModal() { opened = true; }, close() { closed = true; }};
    vm.question = "原来的提问草稿";
    const loading = vm.openSummaryPicker();
    assert.equal(opened, true);
    assert.match(await render(vm), /正在读取资料库/);
    finish(Response.json([
        summaryDocument,
        {id: 39, name: "生成中.pptx", status: "READY", summaryStatus: "PROCESSING"},
        {id: 40, name: "解析中.docx", status: "PROCESSING", summaryStatus: "PENDING"},
        {id: 41, name: "处理失败.pdf", status: "FAILED", summaryStatus: "PENDING"},
        {id: 42, name: "总结失败.pdf", status: "READY", summaryStatus: "FAILED"},
        {id: 43, name: "旧资料.pdf", status: "READY", summaryStatus: null}
    ]));
    await loading;
    const html = await render(vm);
    assert.match(html, /摘要已就绪/);
    assert.match(html, /摘要生成中/);
    assert.match(html, /处理中，发送后等待完成/);
    for (const name of ["处理失败.pdf", "总结失败.pdf", "旧资料.pdf"]) {
        assert.ok(html.includes(`disabled><strong title="${name}"`));
    }
    vm.selectSummaryDocument(vm.summaryDocuments[0]);
    assert.equal(vm.summaryAttachment.id, 38);
    assert.equal(closed, true);
    assert.equal(vm.loadingSummaryDocuments, false);
    assert.equal(vm.messages.length, 0);
    assert.equal(vm.question, "原来的提问草稿");
    assert.match(await render(vm), /数据库.pdf/);
    assert.match(await render(vm), /来自资料库 · 发送后查看总结/);
    assert.match(await render(vm), /aria-label="移除所选资料"/);
    vm.summaryAttachment = null;
    assert.equal(vm.question, "原来的提问草稿");
});

test("summary sends the attachment ID, saves the returned conversation and preserves ordinary drafts", async t => {
    storage(t);
    const bodies = [];
    t.mock.method(globalThis, "fetch", async (url, options) => {
        if (url === "/api/documents/38/summary") return Response.json({documentStatus: "READY", status: "READY"});
        assert.equal(url, "/api/chat/stream");
        const body = JSON.parse(options.body);
        bodies.push(body);
        const answer = body.summaryDocumentId ? "## 已保存摘要\n\n事务保证一致性。" : "普通回答";
        const sources = body.summaryDocumentId
            ? [{documentId: 38, documentName: "数据库.pdf", sourceType: "pdf", sourceUrl: null, chunkIndexes: []}]
            : [];
        return new Response(`event:status\ndata:${JSON.stringify({text: "正在读取已保存的文档摘要"})}\n\n`
            + `event:delta\ndata:${JSON.stringify({text: answer})}\n\n`
            + `event:done\ndata:${JSON.stringify({conversationId: 7, answer, sources, refused: false,
                secondSearchExecuted: false, rewrittenQuestion: null})}\n\n`,
        {headers: {"Content-Type": "text/event-stream"}});
    });
    const vm = createVm();
    vm.summaryAttachment = summaryDocument;
    vm.question = "事务是什么？";
    await vm.askQuestion();

    assert.deepEqual(bodies[0], {conversationId: null, question: "请总结资料《数据库.pdf》", summaryDocumentId: 38});
    assert.equal(vm.conversationId, 7);
    assert.equal(vm.messages[0].content, "请总结资料《数据库.pdf》");
    assert.match(vm.messages[1].content, /已保存摘要/);
    assert.equal(vm.question, "事务是什么？");
    assert.equal(vm.summaryAttachment, null);
    const html = await render(vm);
    assert.match(html, /<h2>已保存摘要<\/h2>/);
    assert.match(html, /文档摘要（模型生成）/);
    assert.doesNotMatch(html, /pdf · 片段/);

    await vm.askQuestion();
    assert.deepEqual(bodies[1], {conversationId: 7, question: "事务是什么？"});
    assert.equal(vm.messages[3].content, "普通回答");
    assert.equal(vm.question, "");
});

test("document list errors stay in the picker and can be retried without changing the current selection", async t => {
    storage(t);
    let fail = true;
    const fetch = t.mock.method(globalThis, "fetch", async (url, options) => {
        assert.equal(url, "/api/documents");
        assert.equal(options, undefined);
        return fail ? Response.json({message: "读取资料失败"}, {status: 500}) : Response.json([summaryDocument]);
    });
    const vm = createVm();
    vm.summaryAttachment = summaryDocument;
    await vm.openSummaryPicker();
    assert.equal(vm.summaryAttachment.id, 38);
    assert.equal(vm.loadingSummaryDocuments, false);
    assert.match(await render(vm), /role="alert">读取资料失败/);
    assert.equal(fetch.mock.callCount(), 1);
    fail = false;
    await vm.openSummaryPicker();
    assert.equal(vm.summaryDocumentsError, "");
    assert.equal(vm.summaryDocuments.length, 1);
    assert.equal(vm.messages.length, 0);
});

test("empty library points to document management instead of a local upload dialog", async t => {
    storage(t);
    t.mock.method(globalThis, "fetch", async () => Response.json([]));
    const vm = createVm();
    await vm.openSummaryPicker();
    assert.match(await render(vm), /还没有资料，请先到<a href="\/">资料库<\/a>上传/);
    assert.equal(vm.messages.length, 0);
});

test("waits for background summary before sending chat and can stop waiting without deleting the attachment", async t => {
    storage(t);
    t.mock.timers.enable({apis: ["setTimeout"]});
    let ready = false;
    const urls = [];
    t.mock.method(globalThis, "fetch", async url => {
        urls.push(url);
        if (url.endsWith("/summary")) return Response.json({documentStatus: "READY", status: ready ? "READY" : "PROCESSING"});
        return new Response(`event:done\ndata:${JSON.stringify({conversationId: 7, answer: "摘要", sources: [], refused: false})}\n\n`,
            {headers: {"Content-Type": "text/event-stream"}});
    });
    const vm = createVm();
    vm.summaryAttachment = summaryDocument;
    const waiting = () => new Promise(resolve => {
        const stop = watch(() => vm.messages.at(-1)?.stage, stage => {
            if (stage?.includes("正在生成文档摘要")) { stop(); resolve(); }
        });
    });
    let started = waiting();
    let pending = vm.askQuestion();
    await started;
    assert.deepEqual(urls, ["/api/documents/38/summary"]);
    assert.equal(vm.sending, true);
    ready = true;
    t.mock.timers.tick(2000);
    await pending;
    assert.equal(urls.at(-1), "/api/chat/stream");
    assert.equal(vm.messages.at(-1).content, "摘要");

    ready = false;
    vm.summaryAttachment = summaryDocument;
    started = waiting();
    pending = vm.askQuestion();
    await started;
    vm.stopGeneration();
    t.mock.timers.tick(2000);
    await pending;
    assert.equal(vm.messages.at(-1).error, "已停止生成");
    assert.equal(vm.summaryAttachment.id, 38);
    assert.equal(urls.filter(url => url === "/api/chat/stream").length, 1);
});

test("parsing, summary failures and deleted documents do not send stale summary requests", async t => {
    storage(t);
    for (const [body, status, message] of [
        [{documentStatus: "FAILED", status: "PENDING", failureReason: "文件没有可提取的文字"}, 200, "文件没有可提取的文字"],
        [{documentStatus: "READY", status: "FAILED", failureReason: "模型超时"}, 200, "模型超时"],
        [{message: "资料不存在：38"}, 404, "资料不存在：38"]
    ]) {
        const fetch = t.mock.method(globalThis, "fetch", async url => {
            assert.equal(url, "/api/documents/38/summary");
            return Response.json(body, {status});
        });
        const vm = createVm();
        vm.summaryAttachment = summaryDocument;
        await vm.askQuestion();
        assert.equal(vm.messages.at(-1).error, message);
        assert.equal(vm.sending, false);
        assert.equal(fetch.mock.callCount(), 1);
        fetch.mock.restore();
    }
});

test("shows send when idle and replaces it with a visible stop button while generating", async t => {
    storage(t);
    const data = ChatApp.data();
    const idle = await render(data);
    assert.match(idle, /<button class="send-button" type="submit"/);
    assert.doesNotMatch(idle, /停止生成/);

    const sending = await render({...data, sending: true});
    assert.match(sending, /<button class="send-button stop-button" type="button"/);
    assert.match(sending, /停止生成/);
    assert.doesNotMatch(sending, /<button class="send-button" type="submit"/);
});

test("stops the pending stream, keeps partial text and restores send", {timeout: 3_000}, async t => {
    storage(t);
    let stream;
    let signal;
    const body = new ReadableStream({start(controller) { stream = controller; }});
    t.mock.method(globalThis, "fetch", async (url, options) => {
        assert.equal(url, "/api/chat/stream");
        signal = options.signal;
        signal.addEventListener("abort", () => stream.error(
            new DOMException("已停止", "AbortError")), {once: true});
        return new Response(body, {headers: {"Content-Type": "text/event-stream"}});
    });
    const vm = reactive({...ChatApp.data(), $refs: {}, $nextTick: nextTick});
    for (const [name, method] of Object.entries(ChatApp.methods)) vm[name] = method.bind(vm);
    vm.question = "问题";
    stream.enqueue(new TextEncoder().encode('event:delta\ndata:{"text":"半截回答"}\n\n'));
    const received = new Promise(resolve => {
        const unwatch = watch(() => vm.messages[1]?.content, text => {
            if (text) { unwatch(); resolve(); }
        });
    });
    const pending = vm.askQuestion();
    // 等到首段已到达，确保验证的是生成中的取消，而不是发送前取消。
    await received;
    assert.equal(vm.sending, true);
    vm.stopGeneration();
    await pending;

    assert.equal(signal.aborted, true);
    assert.equal(vm.messages[1].content, "半截回答");
    assert.equal(vm.messages[1].error, "已停止生成");
    assert.equal(vm.sending, false);
    assert.equal(vm.streamController, null);
    assert.deepEqual(vm.messages[1].sources, []);
    const html = await render({...ChatApp.data(), sending: vm.sending});
    assert.doesNotMatch(html, /class="send-button stop-button"/);
    assert.match(html, /<button class="send-button" type="submit"/);
});
