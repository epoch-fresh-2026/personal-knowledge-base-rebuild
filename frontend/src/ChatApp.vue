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
                            <div v-if="message.secondSearchExecuted" class="retrieval-trace">已执行二次检索：{{ message.rewrittenQuestion }}</div>
                            <section v-if="message.sources && message.sources.length" class="sources-block">
                                <div class="sources-title">◎ 回答来源 · {{ message.sources.length }}</div>
                                <div class="source-list">
                                    <template v-for="source in message.sources" :key="source.documentId">
                                        <a v-if="source.sourceUrl" class="source-card" :href="source.sourceUrl" target="_blank" rel="noopener">
                                            <strong>{{ source.documentName }}</strong><span>{{ source.sourceType }} · 片段 {{ source.chunkIndexes.join(', ') }}</span>
                                        </a>
                                        <div v-else class="source-card">
                                            <strong>{{ source.documentName }}</strong><span>{{ source.sourceType }} · 片段 {{ source.chunkIndexes.join(', ') }}</span>
                                        </div>
                                    </template>
                                </div>
                            </section>
                        </div>
                    </article>
                </div>

                <form class="chat-composer" @submit.prevent="askQuestion">
                    <label class="sr-only" for="question-input">请输入问题</label>
                    <textarea id="question-input" v-model="question" maxlength="1000" rows="1" placeholder="输入关于知识库的问题……"
                              required @keydown.enter.exact.prevent="askQuestion"></textarea>
                    <div class="composer-footer">
                        <span><kbd>Enter</kbd> 发送 · <kbd>Shift + Enter</kbd> 换行</span>
                        <span class="question-count"><b>{{ question.length }}</b>/1000</span>
                        <button v-if="sending" class="send-button stop-button" type="button" @click="stopGeneration"><span>停止生成</span><i aria-hidden="true">■</i></button>
                        <button v-else class="send-button" type="submit" :disabled="loadingConversation"><span>发送</span><i aria-hidden="true">↑</i></button>
                    </div>
                </form>
            </section>
        </main>
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
            streamController: null
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
            const text = this.question.trim();
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
            this.question = "";
            this.sending = true;
            this.streamController = new AbortController();
            await this.scrollToBottom();

            try {
                const result = await streamChat(
                    {conversationId: this.conversationId, question: text},
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
                    secondSearchExecuted: result.secondSearchExecuted,
                    rewrittenQuestion: result.rewrittenQuestion
                });
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
