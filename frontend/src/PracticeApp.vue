<template>
    <div class="app-shell">
        <AppHeader current-page="practice" :health-online="healthOnline"/>

        <main class="practice-page">
            <section class="practice-hero" aria-labelledby="practice-title">
                <div>
                    <p class="eyebrow">LEARN BY RECALLING</p>
                    <h1 id="practice-title">让学过的知识<br><span>真正变成自己的</span></h1>
                    <p>从知识库检索相关资料，生成一道简答题。评分仍以当时检索到的资料为依据，低于 60 分会自动进入错题列表。</p>
                </div>
                <div class="practice-flow" aria-label="自测流程">
                    <span><b>1</b> 检索资料</span><i>→</i><span><b>2</b> 生成题目</span><i>→</i><span><b>3</b> 评分记错题</span>
                </div>
            </section>

            <div class="practice-layout">
                <section class="practice-studio" aria-labelledby="studio-title">
                    <header class="practice-card-head">
                        <div><p class="eyebrow">QUICK PRACTICE</p><h2 id="studio-title">开始一次自测</h2></div>
                        <button v-if="current || result" class="ghost-button" type="button" @click="resetPractice">换个主题</button>
                    </header>

                    <form v-if="!current && !result" class="topic-form" @submit.prevent="createPractice">
                        <label for="practice-topic">你想复习什么知识点？</label>
                        <div class="topic-input-row">
                            <input id="practice-topic" v-model="topic" type="text" maxlength="200"
                                   placeholder="例如：数据库事务、计算机网络 TCP" required>
                            <button class="primary-button" type="submit" :disabled="generating">
                                {{ generating ? "正在查资料…" : "生成题目" }}
                            </button>
                        </div>
                        <p>主题越具体，检索到的资料和题目越准确。</p>
                    </form>

                    <form v-else-if="current && !result" class="question-form" @submit.prevent="submitAnswer">
                        <div class="question-label"><span>简答题</span><small>参考答案已隐藏</small></div>
                        <h3>{{ current.question }}</h3>
                        <label for="practice-answer">你的回答</label>
                        <textarea id="practice-answer" v-model="answer" rows="8" maxlength="5000"
                                  placeholder="不要翻资料，先用自己的话回答……" required></textarea>
                        <div class="answer-actions">
                            <span>{{ answer.length }}/5000</span>
                            <button class="primary-button" type="submit" :disabled="grading">
                                {{ grading ? "正在评分…" : "提交并评分" }}
                            </button>
                        </div>
                    </form>

                    <article v-else-if="result" class="practice-result">
                        <div class="score-line">
                            <div class="score-ring" :class="{passed: !result.needsReview}">
                                <strong>{{ result.score }}</strong><span>分</span>
                            </div>
                            <div>
                                <p class="result-state">{{ resultState(result) }}</p>
                                <h3>{{ result.question }}</h3>
                            </div>
                        </div>

                        <div class="result-block">
                            <span>评分反馈</span>
                            <p>{{ result.feedback }}</p>
                        </div>
                        <div class="result-compare">
                            <div><span>你的回答</span><p>{{ result.userAnswer }}</p></div>
                            <div><span>参考答案</span><p>{{ result.referenceAnswer }}</p></div>
                        </div>
                        <section class="practice-sources" v-if="result.sources?.length">
                            <span>本次出题依据</span>
                            <div>
                                <template v-for="source in result.sources" :key="`${source.documentId}-${source.chunkIndex}`">
                                    <a v-if="source.sourceUrl" :href="source.sourceUrl" target="_blank" rel="noopener">
                                        <strong>{{ source.documentName }}</strong><small>片段 {{ source.chunkIndex }}</small><p>{{ source.excerpt }}</p>
                                    </a>
                                    <article v-else>
                                        <strong>{{ source.documentName }}</strong><small>片段 {{ source.chunkIndex }}</small><p>{{ source.excerpt }}</p>
                                    </article>
                                </template>
                            </div>
                        </section>
                        <button class="primary-button result-next" type="button" @click="resetPractice">再练一道</button>
                    </article>

                    <p v-if="error" class="practice-error" role="alert">{{ error }}</p>
                </section>

                <aside class="mistake-panel" aria-labelledby="mistake-title">
                    <header>
                        <div><p class="eyebrow">REVIEW AGAIN</p><h2 id="mistake-title">错题记忆</h2></div>
                        <span>{{ mistakes.length }} 题</span>
                    </header>
                    <p class="mistake-intro">这里保留低于 60 分的练习，帮助你发现“看过但没有真正掌握”的内容。</p>
                    <div v-if="loadingMistakes" class="mistake-empty"><span class="spinner"></span>正在加载</div>
                    <div v-else-if="mistakes.length === 0" class="mistake-empty">
                        <span class="mistake-empty-icon">✓</span><strong>暂时没有错题</strong><p>完成一次自测后，低分题会出现在这里。</p>
                    </div>
                    <div v-else class="mistake-list">
                        <article v-for="item in mistakes" :key="item.practiceId">
                            <div><span>{{ item.topic }}</span><b>{{ item.score }} 分</b></div>
                            <h3>{{ item.question }}</h3>
                            <p>{{ item.feedback }}</p>
                            <div class="mistake-actions">
                                <small>{{ formatTime(item.createdAt) }}</small>
                                <button type="button" :disabled="retryingId === item.practiceId" @click="retryPractice(item)">
                                    {{ retryingId === item.practiceId ? "正在出题…" : "重新练习" }}
                                </button>
                            </div>
                        </article>
                    </div>
                </aside>
            </div>
        </main>
    </div>
</template>

<script>
import AppHeader from "./AppHeader.vue";
import {jsonRequest, request} from "./api.js";

export default {
    components: {AppHeader},
    data() {
        return {
            topic: "",
            answer: "",
            current: null,
            result: null,
            mistakes: [],
            healthOnline: false,
            generating: false,
            grading: false,
            retryingId: null,
            loadingMistakes: false,
            error: ""
        };
    },
    mounted() {
        this.checkHealth();
        this.loadMistakes();
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
        async createPractice() {
            const topic = this.topic.trim();
            if (!topic || this.generating) return;
            this.generating = true;
            this.error = "";
            try {
                this.current = await request("/api/practices", jsonRequest("POST", {topic}));
            } catch (error) {
                this.error = `生成失败：${error.message}`;
            } finally {
                this.generating = false;
            }
        },
        async submitAnswer() {
            const answer = this.answer.trim();
            if (!answer || this.grading) return;
            this.grading = true;
            this.error = "";
            try {
                this.result = await request(
                    `/api/practices/${this.current.practiceId}/answer`,
                    jsonRequest("POST", {answer})
                );
                this.current = null;
                await this.loadMistakes();
            } catch (error) {
                this.error = `评分失败：${error.message}`;
            } finally {
                this.grading = false;
            }
        },
        async loadMistakes() {
            this.loadingMistakes = true;
            try {
                this.mistakes = await request("/api/practices/mistakes");
            } catch {
                this.mistakes = [];
            } finally {
                this.loadingMistakes = false;
            }
        },
        async retryPractice(item) {
            if (this.retryingId !== null) return;
            this.retryingId = item.practiceId;
            this.error = "";
            try {
                this.current = await request(`/api/practices/${item.practiceId}/retry`, {
                    method: "POST"
                });
                this.topic = item.topic;
                this.answer = "";
                this.result = null;
                await this.$nextTick();
                document.getElementById("studio-title")?.scrollIntoView({behavior: "smooth"});
            } catch (error) {
                this.error = `重练失败：${error.message}`;
            } finally {
                this.retryingId = null;
            }
        },
        resetPractice() {
            this.topic = "";
            this.answer = "";
            this.current = null;
            this.result = null;
            this.error = "";
        },
        formatTime(value) {
            if (!value) return "";
            return new Intl.DateTimeFormat("zh-CN", {
                month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit"
            }).format(new Date(value));
        },
        resultState(result) {
            if (result.retryOfId) {
                return result.needsReview
                    ? "本次仍未通过，原错题继续保留"
                    : "已掌握，已移出错题";
            }
            return result.needsReview ? "已加入错题列表" : "本题通过";
        }
    }
};
</script>
