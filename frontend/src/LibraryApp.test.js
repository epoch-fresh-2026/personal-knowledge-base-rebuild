import {after, before, test} from "node:test";
import assert from "node:assert/strict";
import {createServer} from "vite";
import {createSSRApp, reactive, watch} from "vue";
import {renderToString} from "vue/server-renderer";
import DOMPurify from "dompurify";
import {marked} from "marked";

let vite;
let LibraryApp;

before(async () => {
    vite = await createServer({
        server: {middlewareMode: true, hmr: false, watch: null},
        ssr: {external: ["vue", "vue/server-renderer", "dompurify", "marked"]}
    });
    LibraryApp = (await vite.ssrLoadModule("/src/LibraryApp.vue")).default;
});
after(async () => { await vite?.close(); });

async function render(data) {
    // SSR 没有浏览器 DOM；这里验证 Markdown 布局，净化函数的调用在独立测试中验证。
    const state = Object.fromEntries(Object.keys(LibraryApp.data()).map(key => [key, data[key]]));
    return renderToString(createSSRApp({...LibraryApp, data: () => state,
        methods: {...LibraryApp.methods, renderMarkdown: text => marked.parse(text)}}));
}

function createVm(data = {}) {
    const vm = reactive({...LibraryApp.data(), $refs: {
        summaryDialog: {open: false, showModal() {}},
        relatedDialog: {open: false, showModal() { this.open = true; }}
    }, ...data});
    for (const [name, method] of Object.entries(LibraryApp.methods)) vm[name] = method.bind(vm);
    return vm;
}

const ppt = {id: 1, version: 1, name: "数据库.pptx", fileType: "pptx", status: "READY", summaryStatus: "READY", chunkCount: 2};

test("upload and replacement support the same document formats and ready documents can be summarized", async () => {
    const html = await render({...LibraryApp.data(), documents: [ppt, {...ppt, id: 2, status: "PROCESSING"}]});
    const accepts = [...html.matchAll(/accept="([^"]+)"/g)].map(match => match[1]);
    assert.equal(accepts.length, 2);
    assert.equal(accepts[0], accepts[1]);
    for (const type of [".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx", ".pdf", ".csv", ".odt", ".epub"]) {
        assert.ok(accepts[0].split(",").includes(type));
    }
    assert.equal([...html.matchAll(/title="总结资料"/g)].length, 1);
    assert.equal([...html.matchAll(/title="相关资料"/g)].length, 1);
    assert.match(html, /title="替换文件"/);
    assert.match(html, /单个文件最大 20MB/);
});

test("summary shows loading before response and renders Markdown headings and lists", async t => {
    let complete;
    const pending = new Promise(resolve => { complete = resolve; });
    t.mock.method(globalThis, "fetch", (url, options) => {
        assert.equal(url, "/api/documents/1/summary");
        assert.notEqual(options?.method, "POST");
        return pending;
    });
    let opened = 0;
    const vm = createVm({$refs: {summaryDialog: {showModal() { opened++; }}}});
    const request = LibraryApp.methods.summarizeDocument.call(vm, ppt);
    assert.equal(opened, 1);
    assert.equal(vm.summaries[1].loading, true);
    assert.match(await render(vm), /正在读取已保存的总结/);
    complete(new Response(JSON.stringify({status: "READY", summary: "## 事务与锁\n\n- **事务**保证一致性。"}),
        {headers: {"Content-Type": "application/json"}}));
    await request;
    assert.equal(vm.summaries[1].loading, false);
    assert.match(vm.summaries[1].text, /事务与锁/);
    const html = await render(vm);
    assert.match(html, /<h2>事务与锁<\/h2>/);
    assert.match(html, /<li><strong>事务<\/strong>保证一致性。<\/li>/);
    assert.doesNotMatch(html, /## 事务与锁/);
});

test("Markdown HTML is passed through the existing sanitizer before display", t => {
    const original = Object.getOwnPropertyDescriptor(DOMPurify, "sanitize");
    let received;
    Object.defineProperty(DOMPurify, "sanitize", {configurable: true, value(html, options) {
        received = {html, options};
        return "已净化的 HTML";
    }});
    t.after(() => original ? Object.defineProperty(DOMPurify, "sanitize", original)
        : Reflect.deleteProperty(DOMPurify, "sanitize"));
    assert.equal(LibraryApp.methods.renderMarkdown("## 标题\n<script>alert(1)</script>"), "已净化的 HTML");
    assert.match(received.html, /<h2>标题<\/h2>/);
    assert.deepEqual(received.options, {USE_PROFILES: {html: true}});
});

test("summary errors stay visible and allow retry without altering documents", async t => {
    const fetch = t.mock.method(globalThis, "fetch", async () => new Response(JSON.stringify({message: "总结失败，请重试"}),
        {status: 500, headers: {"Content-Type": "application/json"}}));
    const vm = createVm({documents: [ppt]});
    await LibraryApp.methods.summarizeDocument.call(vm, ppt);
    assert.equal(vm.summaries[1].loading, false);
    assert.equal(vm.summaries[1].error, "总结失败，请重试");
    assert.equal(vm.documents[0].status, "READY");
    const html = await render(vm);
    assert.match(html, /总结失败，请重试/);
    assert.match(html, />重新读取<\/button>/);
    fetch.mock.mockImplementation(async () => Response.json({status: "READY", summary: "已保存的摘要"}));
    await LibraryApp.methods.summarizeDocument.call(vm, ppt);
    assert.equal(vm.summaries[1].error, "");
    assert.equal(vm.summaries[1].text, "已保存的摘要");
});

test("saved summaries can be read independently without mixing responses", {timeout: 3_000}, async t => {
    const second = {...ppt, id: 2, name: "另一份资料.pptx"};
    const finish = new Map();
    const fetch = t.mock.method(globalThis, "fetch", url => new Promise(resolve => { finish.set(url, resolve); }));
    const vm = createVm({documents: [ppt, second]});
    const firstRequest = LibraryApp.methods.summarizeDocument.call(vm, ppt);
    await LibraryApp.methods.summarizeDocument.call(vm, ppt);
    const secondRequest = LibraryApp.methods.summarizeDocument.call(vm, second);
    assert.equal(fetch.mock.callCount(), 2);
    assert.equal(vm.summaries[1].loading, true);
    assert.equal(vm.summaries[2].loading, true);
    const buttons = [...(await render(vm)).matchAll(/<button[^>]*title="总结资料"[^>]*>/g)];
    assert.equal(buttons.length, 2);
    assert.ok(buttons.every(([button]) => !button.includes("disabled")));

    const firstUpdated = new Promise(resolve => {
        const stop = watch(() => vm.summaries[1].loading, loading => {
            if (!loading) { stop(); resolve(); }
        });
    });
    finish.get("/api/documents/1/summary")(Response.json({status: "READY", summary: "第一份的摘要"}));
    await Promise.all([firstRequest, firstUpdated]);
    assert.equal(vm.summaryDocument.id, 2);
    assert.equal(vm.summaries[2].loading, true);
    assert.doesNotMatch(await render(vm), /第一份的摘要/);
    finish.get("/api/documents/2/summary")(Response.json({status: "READY", summary: "第二份的摘要"}));
    await secondRequest;
    assert.match(await render(vm), /第二份的摘要/);

    fetch.mock.mockImplementation(async () => Response.json({status: "READY", summary: "第一份的摘要"}));
    await LibraryApp.methods.summarizeDocument.call(vm, ppt);
    assert.equal(fetch.mock.callCount(), 3);
    assert.match(await render(vm), /第一份的摘要/);
});

test("refresh drops old document versions and late responses cannot restore stale summaries", async t => {
    const updated = {...ppt, version: 2};
    const finish = [];
    t.mock.method(globalThis, "fetch", url => url === "/api/documents"
        ? Promise.resolve(Response.json([updated]))
        : new Promise(resolve => { finish.push(resolve); }));
    const vm = createVm({documents: [ppt]});
    const oldRequest = LibraryApp.methods.summarizeDocument.call(vm, ppt);
    await LibraryApp.methods.loadDocuments.call(vm);
    assert.equal(vm.summaries[1], undefined);
    assert.match(await render(vm), /资料已删除或更新/);
    const newRequest = LibraryApp.methods.summarizeDocument.call(vm, updated);
    finish[0](Response.json({status: "READY", summary: "旧版本摘要"}));
    await oldRequest;
    assert.equal(vm.summaries[1].loading, true);
    assert.equal(vm.summaries[1].text, "");
    finish[1](Response.json({status: "READY", summary: "新版本摘要"}));
    await newRequest;
    assert.equal(vm.summaries[1].text, "新版本摘要");

    t.mock.method(globalThis, "fetch", async () => Response.json([]));
    await LibraryApp.methods.loadDocuments.call(vm);
    assert.equal(vm.summaries[1], undefined);
});

test("polling updates the open summary when background generation completes", async t => {
    let done = false;
    const fetch = t.mock.method(globalThis, "fetch", async (url, options) => {
        assert.notEqual(options?.method, "POST");
        if (url === "/api/documents") return Response.json([{...ppt, version: done ? 2 : 1,
            summaryStatus: done ? "READY" : "PROCESSING"}]);
        return Response.json(done ? {status: "READY", summary: "后台生成并保存的摘要"} : {status: "PROCESSING", summary: null});
    });
    const vm = createVm({documents: [ppt]});
    vm.$refs.summaryDialog.open = true;
    t.after(() => clearTimeout(vm.refreshTimer));
    await vm.summarizeDocument(ppt);
    await vm.loadDocuments();
    assert.match(await render(vm), /后台正在总结完整正文/);
    assert.ok(vm.refreshTimer);
    done = true;
    await vm.loadDocuments();
    assert.match(await render(vm), /后台生成并保存的摘要/);
    assert.equal(vm.summaries[1].status, "READY");
    assert.ok(fetch.mock.calls.every(call => call.arguments[1]?.method !== "POST"));
});

test("failed and legacy summaries can be queued without rebuilding documents", async t => {
    const fetch = t.mock.method(globalThis, "fetch", async (url, options) => {
        if (options?.method === "POST") return Response.json({status: "PENDING"}, {status: 202});
        return Response.json({status: "FAILED", summary: null, failureReason: "模型超时"});
    });
    const vm = createVm({documents: [ppt]});
    await vm.summarizeDocument(ppt);
    assert.match(await render(vm), /模型超时/);
    assert.match(await render(vm), />重试总结<\/button>/);
    let reloads = 0;
    vm.loadDocuments = async () => { reloads++; };
    await vm.retrySummary(ppt);
    assert.equal(reloads, 1);
    assert.equal(fetch.mock.calls.at(-1).arguments[0], "/api/documents/1/summary");
    assert.equal(fetch.mock.calls.at(-1).arguments[1].method, "POST");

    fetch.mock.mockImplementation(async () => Response.json({status: null, summary: null}));
    await vm.summarizeDocument(ppt);
    assert.match(await render(vm), />生成摘要<\/button>/);
});

test("related documents show concepts and shared topics without calling a model", async t => {
    const fetch = t.mock.method(globalThis, "fetch", async (url, options) => {
        assert.notEqual(options?.method, "POST");
        if (url === "/api/documents/1") return Response.json({id: 1, concepts: ["事务", "行锁"]});
        assert.equal(url, "/api/documents/1/related");
        return Response.json([{id: 2, version: 3, name: "并发控制.pptx", sharedConcepts: ["行锁"]}]);
    });
    const vm = createVm({documents: [ppt]});
    await vm.showRelated(ppt);
    assert.equal(vm.$refs.relatedDialog.open, true);
    assert.deepEqual(vm.related.concepts, ["事务", "行锁"]);
    assert.equal(fetch.mock.callCount(), 2);
    const html = await render(vm);
    assert.match(html, /关键概念：事务、行锁/);
    assert.match(html, /并发控制.pptx/);
    assert.match(html, /共同概念：行锁/);
    assert.match(html, /不是回答依据/);
});

test("related view explains pending concepts and empty matches separately", async t => {
    let concepts = [];
    t.mock.method(globalThis, "fetch", async url => Response.json(url.endsWith("/related") ? [] : {concepts}));
    const vm = createVm({documents: [ppt]});
    await vm.showRelated(ppt);
    assert.match(await render(vm), /关键概念尚未生成/);
    concepts = ["行锁"];
    await vm.showRelated(ppt);
    assert.match(await render(vm), /暂未找到具有共同概念的资料/);
    vm.summaryDocument = ppt;
    vm.summaries[1] = {status: "READY", text: "旧摘要"};
    assert.match(await render(vm), />重新生成摘要与概念<\/button>/);
});

test("switching related documents cannot mix late responses", async t => {
    const finish = new Map();
    t.mock.method(globalThis, "fetch", url => new Promise(resolve => finish.set(url, resolve)));
    const vm = createVm({documents: [ppt]});
    const first = vm.showRelated(ppt);
    assert.match(await render(vm), /正在查找共同概念/);
    const second = vm.showRelated({...ppt, id: 2, name: "另一份资料"});
    finish.get("/api/documents/2")(Response.json({concepts: ["Java"]}));
    finish.get("/api/documents/2/related")(Response.json([]));
    await second;
    finish.get("/api/documents/1")(Response.json({concepts: ["旧概念"]}));
    finish.get("/api/documents/1/related")(Response.json([{id: 3, name: "旧关联", sharedConcepts: ["旧概念"]}]));
    await first;
    assert.equal(vm.relatedDocument.id, 2);
    assert.deepEqual(vm.related.concepts, ["Java"]);
    assert.deepEqual(vm.related.documents, []);
});

test("related request errors clear previous matches and allow refresh", async t => {
    const fetch = t.mock.method(globalThis, "fetch", async () => Response.json({message: "资料不存在：1"}, {status: 404}));
    const vm = createVm({documents: [ppt], related: {loading: false, error: "", concepts: ["旧概念"], documents: []}});
    await vm.showRelated(ppt);
    assert.equal(vm.related.loading, false);
    assert.deepEqual(vm.related.concepts, []);
    assert.match(await render(vm), /资料不存在：1/);
    fetch.mock.mockImplementation(async url => Response.json(url.endsWith("/related") ? [] : {concepts: ["新概念"]}));
    await vm.showRelated(ppt);
    assert.equal(vm.related.error, "");
    assert.deepEqual(vm.related.concepts, ["新概念"]);
});
