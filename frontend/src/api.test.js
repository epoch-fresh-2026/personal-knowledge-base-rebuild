import {test} from "node:test";
import assert from "node:assert/strict";
import {streamChat} from "./api.js";

const encoder = new TextEncoder();
const answer = {conversationId: 7, answer: "你好\n世界", sources: [], refused: false};

function event(name, data, newline = "\n") {
    return `event:${name}${newline}data:${JSON.stringify(data)}${newline}${newline}`;
}

function response(stream) {
    return new Response(stream, {headers: {"Content-Type": "text/event-stream;charset=UTF-8"}});
}

test("handles Chinese characters and SSE boundaries split into single bytes", async t => {
    const bytes = encoder.encode(": comment\r\n\r\n"
        + event("status", {text: "正在生成回答"}, "\r\n")
        + event("delta", {text: "你好\n世界"})
        + event("done", answer, "\r\n"));
    const stream = new ReadableStream({
        start(controller) {
            for (const byte of bytes) controller.enqueue(Uint8Array.of(byte));
            controller.close();
        }
    });
    const events = [];
    t.mock.method(globalThis, "fetch", async (url, options) => {
        assert.equal(url, "/api/chat/stream");
        assert.equal(options.method, "POST");
        assert.deepEqual(JSON.parse(options.body), {question: "问题"});
        return response(stream);
    });

    const result = await streamChat({question: "问题"}, (name, data) => events.push([name, data]));

    assert.deepEqual(result, answer);
    assert.deepEqual(events, [["status", {text: "正在生成回答"}], ["delta", {text: "你好\n世界"}]]);
    assert.equal(stream.locked, false);
});

test("delivers partial text before the connection completes", {timeout: 2_000}, async t => {
    let controller;
    const stream = new ReadableStream({start(value) { controller = value; }});
    t.mock.method(globalThis, "fetch", async () => response(stream));
    let firstDelta;
    const received = new Promise(resolve => { firstDelta = resolve; });
    let completed = false;
    const pending = streamChat({}, (name, data) => {
        assert.equal(name, "delta");
        firstDelta(data.text);
    }).then(result => { completed = true; return result; });

    controller.enqueue(encoder.encode(event("delta", {text: "你好"})));
    assert.equal(await received, "你好");
    assert.equal(completed, false);
    controller.enqueue(encoder.encode(event("done", answer)));
    controller.close();
    assert.deepEqual(await pending, answer);
});

test("joins multiline data and cancels the reader after done", async t => {
    let canceled = false;
    const stream = new ReadableStream({
        start(controller) {
            controller.enqueue(encoder.encode('event:done\ndata:{"conversationId":7,\ndata:"answer":"完成"}\n\n'));
        },
        cancel() { canceled = true; }
    });
    t.mock.method(globalThis, "fetch", async () => response(stream));

    assert.deepEqual(await streamChat({}, () => {}), {conversationId: 7, answer: "完成"});
    assert.equal(canceled, true);
    assert.equal(stream.locked, false);
});

test("reports provider errors without treating partial text as a complete answer", async t => {
    let canceled = false;
    const stream = new ReadableStream({
        start(controller) {
            controller.enqueue(encoder.encode(event("delta", {text: "半截回答"})
                + event("error", {message: "回答生成失败"})));
        },
        cancel() { canceled = true; }
    });
    t.mock.method(globalThis, "fetch", async () => response(stream));
    const partial = [];

    await assert.rejects(streamChat({}, (name, data) => partial.push(data.text)), /回答生成失败/);
    assert.deepEqual(partial, ["半截回答"]);
    assert.equal(canceled, true);
    assert.equal(stream.locked, false);
});

test("rejects a connection that ends without done", async t => {
    t.mock.method(globalThis, "fetch", async () => response(new ReadableStream({
        start(controller) {
            controller.enqueue(encoder.encode(event("delta", {text: "半截回答"})));
            controller.close();
        }
    })));

    await assert.rejects(streamChat({}, () => {}), /连接中断，回答尚未完成/);
});

test("reports busy responses and rejects non-SSE responses", async t => {
    const fetch = t.mock.method(globalThis, "fetch", async () => Response.json(
        {message: "当前问答繁忙"}, {status: 503}));
    await assert.rejects(streamChat({}, () => {}), /当前问答繁忙/);
    fetch.mock.mockImplementation(async () => Response.json({answer: "不是事件流"}));
    await assert.rejects(streamChat({}, () => {}), /服务器没有返回事件流/);
});

test("stops reading when the user aborts", {timeout: 2_000}, async t => {
    const abort = new AbortController();
    let controller;
    const stream = new ReadableStream({start(value) { controller = value; }});
    t.mock.method(globalThis, "fetch", async (url, options) => {
        assert.equal(options.signal, abort.signal);
        options.signal.addEventListener("abort", () => controller.error(
            new DOMException("已停止", "AbortError")), {once: true});
        return response(stream);
    });
    let firstDelta;
    const received = new Promise(resolve => { firstDelta = resolve; });
    const pending = streamChat({}, () => firstDelta(), abort.signal);
    controller.enqueue(encoder.encode(event("delta", {text: "第一段"})));
    await received;
    abort.abort();

    await assert.rejects(pending, {name: "AbortError"});
    assert.equal(stream.locked, false);
});
