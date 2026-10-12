<template>
    <div class="app-shell chat-shell">
        <AppHeader current-page="chat" :health-online="healthOnline"/>

        <main class="chat-layout">
            <aside class="chat-sidebar">
                <div class="sidebar-heading">
                    <p class="eyebrow">ASK YOUR LIBRARY</p><h1>与知识<br>重新相遇</h1>
                    <p>回答只来自你的资料，并附上可以追溯的来源。</p>
                </div>
                <div class="sidebar-note"><span>◎</span><div><strong>基于证据回答</strong><p>找不到可靠内容时，知屿会明确拒答，不会凭空编造。</p></div></div>
                <div class="suggestions">
                    <span>提问模板（请替换【】中的内容）</span>
                    <button v-for="suggestion in suggestions" :key="suggestion" type="button" @click="question = suggestion">{{ suggestion }}</button>
                </div>
                <a class="back-library" href="/">← 返回资料管理</a>
            </aside>

            <section class="chat-console" aria-labelledby="chat-title">
                <div class="chat-console-head">
                    <div><span class="chat-avatar">知</span><div><h2 id="chat-title">知识问答</h2><p>Agent 检索 · 来源追踪 · 无依据拒答</p></div></div>
                    <button class="clear-chat" type="button" :disabled="sending || loadingConversation" @click="clearConversation">清空对话</button>
                </div>

                <div class="messages" ref="messages" aria-live="polite">
                    <div class="chat-empty" v-if="messages.length === 0">
                        <span class="empty-orbit"><i>知</i></span><h2>向你的知识岛提问</h2>
                        <p>我会先检索已有资料，必要时改写问题再次查找，最后给出带来源的回答。</p>
                    </div>
                    <article v-for="(message, index) in messages" :key="index" class="message" :class="[message.role, {refused: message.refused}]">
                        <span v-if="message.role === 'assistant'" class="message-icon">知</span>
                        <div class="message-content">
                            <div v-if="message.role === 'assistant' && !message.loading && message.content" class="message-bubble markdown-answer"
                                 v-html="renderMarkdown(message.content)"></div>
                            <div v-else-if="message.content" class="message-bubble">{{ message.content }}</div>
                            <div v-if="message.loading" class="retrieval-trace" role="status">{{ message.stage }}</div>
                            <div v-if="message.error" class="retrieval-trace" role="alert">回答未完成：{{ message.error }}</div>
                            <div v-if="message.searchTasks && message.searchTasks.length === 2" class="retrieval-trace">并行查询：{{ message.searchTasks.join('；') }}</div>
                            <section v-if="message.sources && message.sources.length" class="sources-block">
                                <div class="sources-title">◎ 回答来源 · {{ message.sources.length }}</div>
                                <div class="source-list">
                                    <template v-for="source in message.sources" :key="source.documentId">
                                        <a v-if="source.sourceUrl" class="source-card" :href="source.sourceUrl" target="_blank" rel="noopener">
                                            <strong>{{ source.documentName }}</strong><span>{{ source.sourceType }} · {{ source.chunkIndexes.length ? '片段 ' + source.chunkIndexes.join(', ') : '文档摘要（模型生成）' }}</span>
                                        </a>
                                        <div v-else class="source-card">
                                            <strong>{{ source.documentName }}</strong><span>{{ source.sourceType }} · {{ source.chunkIndexes.length ? '片段 ' + source.chunkIndexes.join(', ') : '文档摘要（模型生成）' }}</span>
                                        </div>
                                    </template>
                                </div>
                            </section>
                        </div>
                    </article>
                </div>

                <form class="chat-composer" @submit.prevent="askQuestion">
                    <div v-if="summaryAttachment" class="composer-attachment" role="status">
                        <span class="attachment-icon" aria-hidden="true">文</span>
                        <div class="attachment-info">
                            <strong :title="summaryAttachment.name">{{ summaryAttachment.name }}</strong>
                            <span>来自资料库 · 发送后查看总结</span>
                        </div>
                        <button class="attachment-remove" type="button" aria-label="移除所选资料"
                                :disabled="sending" @click="summaryAttachment = null">×</button>
                    </div>
                    <label class="sr-only" for="question-input">请输入问题</label>
                    <textarea id="question-input" :value="summaryAttachment ? '' : question" @input="question = $event.target.value"
                              maxlength="1000" rows="1" :readonly="!!summaryAttachment" :required="!summaryAttachment"
                              :placeholder="summaryAttachment ? '点击发送，总结这份资料……' : '输入关于知识库的问题……'"
                              @keydown.enter.exact.prevent="askQuestion"></textarea>
                    <div class="composer-footer">
                        <button class="summary-select-button" type="button" :disabled="sending || loadingConversation"
                                @click="openSummaryPicker"><span aria-hidden="true">＋</span>总结资料</button>
                        <span class="composer-shortcut"><kbd>Enter</kbd> 发送 · <kbd>Shift + Enter</kbd> 换行</span>
                        <span v-if="!summaryAttachment" class="question-count"><b>{{ question.length }}</b>/1000</span>
                        <button v-if="sending" class="send-button stop-button" type="button" @click="stopGeneration"><span>停止生成</span><i aria-hidden="true">■</i></button>
                        <button v-else class="send-button" type="submit" :disabled="loadingConversation"><span>发送</span><i aria-hidden="true">↑</i></button>
                    </div>
                </form>
            </section>
        </main>

        <dialog ref="summaryPicker" class="confirm-dialog summary-select-dialog" aria-labelledby="summary-picker-title">
            <h2 id="summary-picker-title">选择已上传的资料</h2>
            <p>选好后，点击聊天输入框的“发送”查看总结。</p>
            <p v-if="loadingSummaryDocuments" role="status">正在读取资料库……</p>
            <p v-else-if="summaryDocumentsError" class="document-error" role="alert">{{ summaryDocumentsError }}</p>
            <ul v-else-if="summaryDocuments.length" class="summary-document-list">
                <li v-for="item in summaryDocuments" :key="item.id">
                    <button type="button" :disabled="item.status === 'FAILED' || (item.status === 'READY' && !['PENDING', 'PROCESSING', 'READY'].includes(item.summaryStatus))"
                            @click="selectSummaryDocument(item)">
                        <strong :title="item.name">{{ item.name }}</strong>
                        <span>{{ summaryStatusText(item) }}</span>
                    </button>
                </li>
            </ul>
            <p v-else>还没有资料，请先到<a href="/">资料库</a>上传。</p>
            <p v-if="summaryDocuments.length" class="form-hint">失败或尚无摘要的资料，请先在资料库重试或生成摘要。</p>
            <div class="dialog-actions">
                <button class="ghost-button" type="button" @click="$refs.summaryPicker.close()">取消</button>
                <button v-if="summaryDocumentsError" class="primary-button" type="button" @click="openSummaryPicker">重新读取</button>
            </div>
        </dialog>
    </div>
</template>

<script>
import DOMPurify from "dompurify";
import {marked} from "marked";
import AppHeader from "./AppHeader.vue";
import {request, streamChat} from "./api.js";

const CONVERSATION_KEY = "knowledge-island-conversation-id";

export default {
    components: {AppHeader},
    data() {
        return {
            suggestions: [
                "【资料中的具体概念】是什么？",
                "【概念 A】和【概念 B】有什么区别？",
                "根据知识库资料说明【一个具体问题】。"
            ],
            question: "",
            messages: [],
            conversationId: Number(localStorage.getItem(CONVERSATION_KEY)) || null,
            healthOnline: false,
            loadingConversation: false,
            sending: false,
            streamController: null,
            summaryAttachment: null,
            summaryDocuments: [],
            loadingSummaryDocuments: false,
            summaryDocumentsError: ""
        };
    },
    mounted() {
        this.checkHealth();
        this.loadConversation();
    },
    beforeUnmount() {
        this.stopGeneration();
    },
    methods: {
        async openSummaryPicker() {
            this.$refs.summaryPicker.showModal();
            this.loadingSummaryDocuments = true;
            this.summaryDocumentsError = "";
            try {
                this.summaryDocuments = await request("/api/documents");
            } catch (error) {
                this.summaryDocumentsError = error.message;
            } finally {
                this.loadingSummaryDocuments = false;
            }
        },
        selectSummaryDocument(document) {
            this.summaryAttachment = document;
            this.$refs.summaryPicker.close();
        },
        summaryStatusText(document) {
            if (document.status !== "READY") return document.status === "FAILED" ? "处理失败" : "处理中，发送后等待完成";
            return {READY: "摘要已就绪", PENDING: "摘要生成中", PROCESSING: "摘要生成中", FAILED: "总结失败"}[document.summaryStatus]
                || "尚无摘要";
        },
        async waitForSummary(documentId, pending, signal) {
            while (true) {
                signal.throwIfAborted();
                const result = await request(`/api/documents/${documentId}/summary`, {signal});
                if (result.documentStatus === "FAILED" || result.status === "FAILED") {
                    throw new Error(result.failureReason || "资料处理失败，请在资料库中重试");
                }
                if (result.documentStatus === "READY" && result.status === "READY") return;
                pending.stage = result.documentStatus === "READY"
                    ? "正在生成文档摘要，完成后自动显示……" : "正在解析资料并建立索引……";
                await new Promise(resolve => setTimeout(resolve, 2000));
            }
        },
        async checkHealth() {
            try {
                await request("/api/health");
                this.healthOnline = true;
            } catch {
                this.healthOnline = false;
            }
        },
        async loadConversation() {
            if (!this.conversationId) return;
            this.loadingConversation = true;
            try {
                const conversation = await request(`/api/chat/${this.conversationId}`);
                this.messages = conversation.messages.map(message => ({
                    role: message.role,
                    content: message.content,
                    refused: message.refused,
                    sources: message.sources || []
                }));
                await this.scrollToBottom();
            } catch {
                this.clearConversation();
            } finally {
                this.loadingConversation = false;
            }
        },
        async askQuestion() {
            const document = this.summaryAttachment;
            await this.sendQuestion(document ? `请总结资料《${document.name}》` : this.question.trim(), document?.id);
        },
        async sendQuestion(text, summaryDocumentId = null) {
            if (!text || this.sending || this.loadingConversation) return;

            this.messages.push({role: "user", content: text});
            this.messages.push({
                role: "assistant",
                content: "",
                stage: "已提交，等待处理……",
                loading: true,
                refused: false,
                sources: []
            });
            // 从 Vue 数组取回响应式对象，确保每个片段到达时页面立即更新。
            const pending = this.messages[this.messages.length - 1];
            if (summaryDocumentId === null) this.question = "";
            this.sending = true;
            this.streamController = new AbortController();
            await this.scrollToBottom();

            try {
                const body = {conversationId: this.conversationId, question: text};
                if (summaryDocumentId !== null) {
                    body.summaryDocumentId = summaryDocumentId;
                    await this.waitForSummary(summaryDocumentId, pending, this.streamController.signal);
                }
                const result = await streamChat(
                    body,
                    (event, data) => {
                        if (event === "status") pending.stage = data.text;
                        if (event === "delta") pending.content += data.text;
                        this.scrollToBottom();
                    },
                    this.streamController.signal
                );
                this.conversationId = result.conversationId;
                localStorage.setItem(CONVERSATION_KEY, this.conversationId);
                Object.assign(pending, {
                    content: result.answer,
                    loading: false,
                    refused: result.refused,
                    sources: result.sources,
                    searchTasks: result.searchTasks
                });
                if (summaryDocumentId !== null) this.summaryAttachment = null;
            } catch (error) {
                Object.assign(pending, {
                    error: error.name === "AbortError" ? "已停止生成" : error.message,
                    loading: false,
                    sources: []
                });
            } finally {
                this.streamController = null;
                this.sending = false;
                await this.scrollToBottom();
            }
        },
        stopGeneration() {
            this.streamController?.abort();
        },
        clearConversation() {
            this.conversationId = null;
            this.messages = [];
            localStorage.removeItem(CONVERSATION_KEY);
        },
        renderMarkdown(text) {
            return DOMPurify.sanitize(marked.parse(text), {USE_PROFILES: {html: true}});
        },
        async scrollToBottom() {
            await this.$nextTick();
            const container = this.$refs.messages;
            if (container) container.scrollTop = container.scrollHeight;
        }
    }
};
</script>
