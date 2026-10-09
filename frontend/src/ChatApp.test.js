import {after, before, test} from "node:test";
import assert from "node:assert/strict";
import {createServer} from "vite";
import {createSSRApp, nextTick, reactive, watch} from "vue";
import {renderToString} from "vue/server-renderer";

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
    return renderToString(createSSRApp({...ChatApp, data: () => data}));
}

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
