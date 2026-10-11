<template>
    <div class="app-shell">
        <AppHeader current-page="library" :health-online="healthOnline"/>

        <main>
            <section class="hero" aria-labelledby="page-title">
                <div class="hero-copy">
                    <p class="eyebrow">YOUR SECOND BRAIN</p>
                    <h1 id="page-title">把散落的知识<br><span>收进一座岛屿</span></h1>
                    <p class="hero-description">上传文档、记录灵感、收藏网页。系统会自动解析、切分并建立向量索引，为之后的知识库问答做好准备。</p>
                </div>
                <div class="stats-panel" aria-label="知识库统计">
                    <div class="stat-card"><span class="stat-icon blue">▤</span><div><strong>{{ documents.length }}</strong><span>全部资料</span></div></div>
                    <div class="stat-card"><span class="stat-icon green">✓</span><div><strong>{{ readyCount }}</strong><span>已完成索引</span></div></div>
                    <div class="stat-card wide"><span class="stat-icon amber">◫</span><div><strong>{{ chunkCount }}</strong><span>知识片段</span></div></div>
                </div>
            </section>

            <section class="workspace" aria-labelledby="add-resource-title">
                <div class="section-heading">
                    <div><p class="eyebrow">ADD KNOWLEDGE</p><h2 id="add-resource-title">添加新资料</h2></div>
                    <p>选择一种方式，将内容收录到你的知识库</p>
                </div>
                <div class="composer-card">
                    <div class="composer-tabs" role="tablist">
                        <button v-for="tab in tabs" :key="tab.id" class="composer-tab" :class="{active: activeTab === tab.id}"
                                type="button" :aria-selected="activeTab === tab.id" @click="activeTab = tab.id">
                            <span>{{ tab.icon }}</span> {{ tab.label }}
                        </button>
                    </div>

                    <div class="tab-panel" :class="{active: activeTab === 'file'}" v-show="activeTab === 'file'">
                        <form ref="fileForm" @submit.prevent="uploadFile">
                            <label class="drop-zone" for="file-input">
                                <input id="file-input" name="file" type="file" :accept="fileAccept" required @change="selectFile">
                                <span class="upload-symbol">↑</span>
                                <strong>{{ fileName || "拖放文件到这里，或点击选择" }}</strong>
                                <span>支持 Word、PPT、Excel、PDF、Markdown 等文字文档（不含 OCR）</span>
                            </label>
                            <div class="form-actions"><span class="form-hint">单个文件最大 20MB，上传后自动解析、建立索引并生成摘要</span>
                                <button class="primary-button" type="submit" :disabled="submitting">上传并入库</button></div>
                        </form>
                    </div>

                    <div class="tab-panel" :class="{active: activeTab === 'note'}" v-show="activeTab === 'note'">
                        <form class="stacked-form" @submit.prevent="createNote">
                            <label><span>笔记标题</span><input v-model="note.title" type="text" maxlength="255" placeholder="例如：Spring AI 学习笔记" required></label>
                            <label><span>笔记正文</span><textarea v-model="note.content" rows="7" placeholder="写下值得被检索的内容……" required></textarea></label>
                            <div class="form-actions"><span class="form-hint">保存后即可参与知识库检索</span>
                                <button class="primary-button" type="submit" :disabled="submitting">保存笔记</button></div>
                        </form>
                    </div>

                    <div class="tab-panel" :class="{active: activeTab === 'link'}" v-show="activeTab === 'link'">
                        <form class="stacked-form" @submit.prevent="createLink">
                            <label><span>网页地址</span><input v-model="link.url" type="url" maxlength="2048" placeholder="https://example.com/article" required></label>
                            <label><span>自定义标题 <em>选填</em></span><input v-model="link.title" type="text" maxlength="255" placeholder="留空时自动使用网页标题"></label>
                            <div class="form-actions"><span class="form-hint">仅支持可公开访问的 HTTP/HTTPS 网页</span>
                                <button class="primary-button" type="submit" :disabled="submitting">抓取并收藏</button></div>
                        </form>
                    </div>
                </div>
            </section>

            <section class="library" aria-labelledby="library-title">
                <div class="section-heading library-heading">
                    <div><p class="eyebrow">YOUR LIBRARY</p><h2 id="library-title">我的资料</h2></div>
                    <button class="ghost-button" type="button" @click="loadDocuments">↻ 刷新</button>
                </div>
                <div class="library-state" v-show="loading"><span class="spinner"></span><span>正在加载资料……</span></div>
                <div class="library-state empty-state" v-if="!loading && documents.length === 0">
                    <span class="empty-illustration">◇</span><strong>知识岛还是空的</strong><p>从上方上传第一份文件、笔记或网页吧。</p>
                </div>
                <div class="document-grid" aria-live="polite">
                    <article v-for="item in documents" :key="item.id" class="document-card" :style="{'--type-color': typeInfo(item)[1]}">
                        <div class="document-card-head">
                            <span class="type-badge">{{ typeInfo(item)[0] }}</span>
                            <div class="document-actions">
                                <button v-if="item.status === 'READY'" type="button" title="总结资料"
                                        @click="summarizeDocument(item)">{{ ['PENDING', 'PROCESSING'].includes(item.summaryStatus) ? "总结中…" : "查看总结" }}</button>
                                <button v-if="item.status === 'FAILED'" type="button" title="重新处理" @click="retryDocument(item)">↻</button>
                                <button v-if="item.fileType === 'note'" type="button" title="修改笔记" @click="openNoteEditor(item)">✎</button>
                                <button v-if="isFile(item)" type="button" title="替换文件" @click="openFileDialog(item)">↥</button>
                                <button class="delete-button" type="button" title="删除资料" @click="openDeleteDialog(item)">×</button>
                            </div>
                        </div>
                        <h3>{{ item.name }}</h3>
                        <p v-if="item.sourceUrl" class="document-url">{{ item.sourceUrl }}</p>
                        <p v-if="item.failureReason" class="document-error">{{ item.failureReason }}</p>
                        <div class="document-meta">
                            <span class="ready-pill">{{ statusText[item.status] || item.status }}</span>
                            <span>{{ item.chunkCount }} 个片段</span><time>{{ formatDate(item.uploadTime) }}</time>
                        </div>
                    </article>
                </div>
            </section>
        </main>

        <footer><span>知屿 Personal Knowledge Base</span><span>Spring Boot · PostgreSQL · pgvector · Spring AI</span></footer>

        <dialog class="confirm-dialog" ref="deleteDialog">
            <span class="dialog-icon">!</span><h2>删除这份资料？</h2>
            <p>“<strong>{{ selectedDocument ? selectedDocument.name : "" }}</strong>”及其向量索引会被一并删除，此操作无法撤销。</p>
            <div class="dialog-actions">
                <button class="ghost-button" type="button" @click="$refs.deleteDialog.close()">取消</button>
                <button class="danger-button" type="button" @click="deleteDocument">确认删除</button>
            </div>
        </dialog>

        <dialog class="confirm-dialog edit-dialog" ref="noteDialog">
            <form class="stacked-form" @submit.prevent="updateNote">
                <h2>修改笔记</h2><p>保存后会重新切分正文并更新向量索引。</p>
                <label><span>笔记标题</span><input v-model="editNote.name" type="text" maxlength="255" required></label>
                <label><span>笔记正文</span><textarea v-model="editNote.content" rows="9" required></textarea></label>
                <div class="dialog-actions">
                    <button class="ghost-button" type="button" @click="$refs.noteDialog.close()">取消</button>
                    <button class="primary-button" type="submit">保存修改</button>
                </div>
            </form>
        </dialog>

        <dialog class="confirm-dialog edit-dialog" ref="fileDialog">
            <form ref="replaceForm" class="stacked-form" @submit.prevent="replaceFile">
                <h2>替换文件</h2><p>为“<strong>{{ selectedDocument ? selectedDocument.name : "" }}</strong>”选择一个新文件（最大 20MB），原有向量索引会被同步替换。</p>
                <label><span>新文件</span><input name="file" type="file" :accept="fileAccept" required></label>
                <div class="dialog-actions">
                    <button class="ghost-button" type="button" @click="$refs.fileDialog.close()">取消</button>
                    <button class="primary-button" type="submit">确认替换</button>
                </div>
            </form>
        </dialog>

        <dialog class="confirm-dialog edit-dialog summary-dialog" ref="summaryDialog">
            <h2>文档总结</h2><p>{{ summaryDocument?.name }}</p>
            <p v-if="currentSummary?.loading" role="status">正在读取已保存的总结……</p>
            <p v-else-if="['PENDING', 'PROCESSING'].includes(currentSummary?.status)" role="status">后台正在总结完整正文，完成后会自动显示。可以关闭窗口，继续查看其他资料。</p>
            <p v-else-if="currentSummary?.error" class="document-error" role="alert">{{ currentSummary.error }}</p>
            <div v-else-if="currentSummary?.text" class="summary-content markdown-answer"
                 v-html="renderMarkdown(currentSummary.text)"></div>
            <p v-else-if="currentSummary">这份资料还没有摘要，点击下方按钮在后台生成。</p>
            <p v-else-if="summaryDocument" class="document-error" role="alert">资料已删除或更新，请重新选择资料。</p>
            <p class="form-hint">模型总结仅供参考，请结合原文核对。摘要保存在数据库中，不覆盖原文或写入向量索引。</p>
            <div class="dialog-actions">
                <button class="ghost-button" type="button" @click="$refs.summaryDialog.close()">关闭</button>
                <button v-if="currentSummary && !currentSummary.loading && !currentSummary.status && !currentSummary.error" class="primary-button" type="button"
                        @click="retrySummary(summaryDocument)">生成摘要</button>
                <button v-else-if="currentSummary?.status === 'FAILED'" class="primary-button" type="button"
                        @click="retrySummary(summaryDocument)">重试总结</button>
                <button v-else-if="currentSummary?.error" class="primary-button" type="button"
                        @click="loadSummary(summaryDocument)">重新读取</button>
            </div>
        </dialog>

        <div class="toast" :class="[toast.error ? 'error' : 'success', {visible: toast.visible}]" role="status">{{ toast.message }}</div>
    </div>
</template>

<script>
import DOMPurify from "dompurify";
import {marked} from "marked";
import AppHeader from "./AppHeader.vue";
import {jsonRequest, request} from "./api.js";

const API = "/api/documents";
const FILE_TYPES = ["txt", "md", "markdown", "pdf", "doc", "docx", "ppt", "pptx",
    "xls", "xlsx", "csv", "tsv", "html", "htm", "rtf", "odt", "ods", "odp", "epub"];
const TYPE_INFO = {
    txt: ["TXT", "#287f96"],
    md: ["MARKDOWN", "#765aa3"],
    markdown: ["MARKDOWN", "#765aa3"],
    pdf: ["PDF", "#c45151"],
    docx: ["DOCX", "#3f6fa8"],
    note: ["笔记", "#b27828"],
    web: ["网页", "#258269"]
};

export default {
    components: {AppHeader},
    data() {
        return {
            tabs: [
                {id: "file", icon: "↑", label: "上传文件"},
                {id: "note", icon: "✎", label: "创建笔记"},
                {id: "link", icon: "↗", label: "收藏网页"}
            ],
            statusText: {
                PENDING: "等待处理",
                PROCESSING: "正在处理",
                READY: "已完成索引",
                FAILED: "处理失败"
            },
            activeTab: "file",
            documents: [],
            loading: true,
            submitting: false,
            healthOnline: false,
            fileName: "",
            note: {title: "", content: ""},
            link: {url: "", title: ""},
            editNote: {name: "", content: ""},
            selectedDocument: null,
            summaryDocument: null,
            summaries: {},
            toast: {visible: false, error: false, message: ""},
            refreshTimer: null,
            toastTimer: null
        };
    },
    computed: {
        currentSummary() {
            return this.summaries[this.summaryDocument?.id];
        },
        fileAccept() {
            return FILE_TYPES.map(type => `.${type}`).join(",");
        },
        readyCount() {
            return this.documents.filter(item => item.status === "READY").length;
        },
        chunkCount() {
            return this.documents.reduce((sum, item) => sum + item.chunkCount, 0);
        }
    },
    mounted() {
        this.checkHealth();
        this.loadDocuments();
    },
    beforeUnmount() {
        clearTimeout(this.refreshTimer);
        clearTimeout(this.toastTimer);
    },
    methods: {
        async summarizeDocument(item) {
            this.summaryDocument = item;
            this.$refs.summaryDialog.showModal();
            await this.loadSummary(item);
        },
        async loadSummary(item) {
            const previous = this.summaries[item.id];
            if (previous?.loading && previous.version === item.version) return;

            this.summaries[item.id] = {version: item.version, loading: true, status: item.summaryStatus, text: "", error: ""};
            // 从 Vue 取回响应式对象；各请求只更新自己的资料，不覆盖当前打开的其他资料。
            const summary = this.summaries[item.id];
            try {
                const result = await request(`${API}/${item.id}/summary`);
                summary.text = result.summary;
                summary.status = result.status;
                summary.error = result.failureReason || "";
            } catch (error) {
                summary.error = error.message;
            } finally {
                summary.loading = false;
            }
        },
        async retrySummary(item) {
            try {
                await request(`${API}/${item.id}/summary`, {method: "POST"});
                await this.loadDocuments();
            } catch (error) {
                this.showToast(error.message, true);
            }
        },
        renderMarkdown(text) {
            return DOMPurify.sanitize(marked.parse(text), {USE_PROFILES: {html: true}});
        },
        async checkHealth() {
            try {
                await request("/api/health");
                this.healthOnline = true;
            } catch {
                this.healthOnline = false;
            }
        },
        async loadDocuments() {
            clearTimeout(this.refreshTimer);
            this.loading = true;
            try {
                this.documents = await request(API);
                // 删除、替换或编辑资料后，清掉当前页面里旧版本的总结。
                for (const [id, summary] of Object.entries(this.summaries)) {
                    const document = this.documents.find(item => item.id === Number(id));
                    if (!document || document.version !== summary.version) delete this.summaries[id];
                }
                if (this.summaryDocument && this.$refs.summaryDialog.open) {
                    const selected = this.documents.find(item => item.id === this.summaryDocument.id);
                    if (selected) {
                        this.summaryDocument = selected;
                        await this.loadSummary(selected);
                    }
                }
                if (this.documents.some(item => ["PENDING", "PROCESSING"].includes(item.status)
                        || (item.status === "READY" && ["PENDING", "PROCESSING"].includes(item.summaryStatus)))) {
                    this.refreshTimer = setTimeout(() => this.loadDocuments(), 2000);
                }
            } catch (error) {
                this.showToast(error.message, true);
            } finally {
                this.loading = false;
            }
        },
        selectFile(event) {
            this.fileName = event.target.files[0]?.name || "";
        },
        async uploadFile() {
            const form = this.$refs.fileForm;
            await this.submit(
                () => request(API, {method: "POST", body: new FormData(form)}),
                () => {
                    form.reset();
                    this.fileName = "";
                },
                "文件已提交，正在后台处理"
            );
        },
        async createNote() {
            await this.submit(
                () => request(`${API}/notes`, jsonRequest("POST", this.note)),
                () => this.note = {title: "", content: ""},
                "笔记已提交，正在后台处理"
            );
        },
        async createLink() {
            await this.submit(
                () => request(`${API}/links`, jsonRequest("POST", this.link)),
                () => this.link = {url: "", title: ""},
                "网页已提交，正在后台处理"
            );
        },
        async submit(action, clearForm, message) {
            this.submitting = true;
            try {
                await action();
                clearForm();
                this.showToast(message);
                await this.loadDocuments();
            } catch (error) {
                this.showToast(error.message, true);
            } finally {
                this.submitting = false;
            }
        },
        async retryDocument(item) {
            try {
                await request(`${API}/${item.id}/retry`, {method: "POST"});
                this.showToast("已重新提交处理");
                await this.loadDocuments();
            } catch (error) {
                this.showToast(error.message, true);
            }
        },
        async openNoteEditor(item) {
            try {
                this.selectedDocument = await request(`${API}/${item.id}`);
                this.editNote = {name: this.selectedDocument.name, content: this.selectedDocument.content};
                this.$refs.noteDialog.showModal();
            } catch (error) {
                this.showToast(error.message, true);
            }
        },
        async updateNote() {
            try {
                await request(`${API}/${this.selectedDocument.id}`, jsonRequest("PUT", this.editNote));
                this.$refs.noteDialog.close();
                this.showToast("修改已提交，正在后台处理");
                await this.loadDocuments();
            } catch (error) {
                this.showToast(error.message, true);
            }
        },
        openFileDialog(item) {
            this.selectedDocument = item;
            this.$refs.replaceForm.reset();
            this.$refs.fileDialog.showModal();
        },
        async replaceFile() {
            try {
                await request(`${API}/${this.selectedDocument.id}`, {
                    method: "PUT",
                    body: new FormData(this.$refs.replaceForm)
                });
                this.$refs.fileDialog.close();
                this.showToast("替换已提交，正在后台处理");
                await this.loadDocuments();
            } catch (error) {
                this.showToast(error.message, true);
            }
        },
        openDeleteDialog(item) {
            this.selectedDocument = item;
            this.$refs.deleteDialog.showModal();
        },
        async deleteDocument() {
            try {
                await request(`${API}/${this.selectedDocument.id}`, {method: "DELETE"});
                this.$refs.deleteDialog.close();
                this.showToast("资料已删除");
                await this.loadDocuments();
            } catch (error) {
                this.showToast(error.message, true);
            }
        },
        isFile(item) {
            return FILE_TYPES.includes(item.fileType);
        },
        typeInfo(item) {
            return TYPE_INFO[item.fileType] || [item.fileType.toUpperCase(), "#60758a"];
        },
        formatDate(value) {
            return new Date(value).toLocaleDateString("zh-CN");
        },
        showToast(message, error = false) {
            clearTimeout(this.toastTimer);
            this.toast = {visible: true, error, message};
            this.toastTimer = setTimeout(() => this.toast.visible = false, 3000);
        }
    }
};
</script>

<style scoped>
.summary-dialog {
    padding: 30px;
}
.summary-content {
    white-space: normal;
    text-align: left;
    line-height: 1.8;
    max-height: 50vh;
    overflow-y: auto;
    overflow-wrap: anywhere;
}
</style>
