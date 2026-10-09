export async function request(url, options) {
    const response = await fetch(url, options);
    if (response.status === 204) return;

    const body = await response.json();
    if (!response.ok) throw new Error(body.message);
    return body;
}

export function jsonRequest(method, body) {
    return {
        method,
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(body)
    };
}

// POST 带问题和会话 ID，因此用 fetch 读取 SSE，而不是只支持 GET 的 EventSource。
export async function streamChat(body, onEvent, signal) {
    const response = await fetch("/api/chat/stream", {
        ...jsonRequest("POST", body),
        headers: {"Content-Type": "application/json", "Accept": "text/event-stream"},
        signal
    });
    if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.message || `请求失败（${response.status}）`);
    }
    if (!response.body || !response.headers.get("Content-Type")?.includes("text/event-stream")) {
        throw new Error("服务器没有返回事件流");
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    try {
        while (true) {
            const {value, done} = await reader.read();
            // 网络分块可能从一个汉字或一个 SSE 事件中间断开，先解码、缓存再解析。
            buffer += decoder.decode(value, {stream: !done});
            let boundary;
            while ((boundary = /\r\n\r\n|\n\n|\r\r/.exec(buffer))) {
                const frame = buffer.slice(0, boundary.index);
                buffer = buffer.slice(boundary.index + boundary[0].length);
                let event = "message";
                const lines = [];
                for (const line of frame.split(/\r\n|\r|\n/)) {
                    if (line.startsWith("event:")) event = line.slice(6).trim();
                    if (line.startsWith("data:")) lines.push(line.slice(5).replace(/^ /, ""));
                }
                if (!lines.length) continue; // 忽略空事件或 SSE 注释。
                const data = JSON.parse(lines.join("\n"));
                if (event === "error") throw new Error(data.message || "回答生成失败");
                if (event === "done") return data;
                if (event === "status" || event === "delta") onEvent(event, data);
            }
            if (buffer.length > 1_000_000) throw new Error("事件数据过大");
            if (done) throw new Error("连接中断，回答尚未完成，请重试");
        }
    } finally {
        await reader.cancel().catch(() => {});
        reader.releaseLock();
    }
}
