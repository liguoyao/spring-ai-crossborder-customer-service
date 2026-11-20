<template>
  <a-card title="A2A 通信任务" size="small" class="a2a-panel">
    <a-space wrap>
      <a-input v-if="!conversationUuid" v-model:value="query" placeholder="输入会话 UUID 查看真实通信" style="width: 300px" />
      <a-button :loading="loading" :disabled="!selectedConversation" @click="load">查询任务</a-button>
      <span>当前租户的任务记录与内部审批关联</span>
    </a-space>
    <a-alert v-if="error" :message="error" type="error" show-icon />
    <a-empty v-if="!loading && !tasks.length" description="暂无 A2A 通信任务" />
    <a-timeline v-else class="task-timeline">
      <a-timeline-item v-for="task in tasks" :key="task.id">
        <strong>{{ task.agent_key }}</strong>
        <a-tag>{{ task.state }}</a-tag><a-tag>{{ task.mode }}</a-tag>
        <div>{{ task.summary }}</div>
        <div class="task-meta">任务 {{ task.id }} · 上游 {{ task.parent_task_id || 'Supervisor' }}</div>
        <div class="task-meta">{{ task.created_at }} → {{ task.updated_at }}</div>
        <div v-if="task.failureReason">原因：{{ task.failureReason }}</div>
        <div v-if="task.approvalRequestNo">内部审批单：{{ task.approvalRequestNo }}（申请提交不代表退款已执行）</div>
      </a-timeline-item>
    </a-timeline>
  </a-card>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import api from '@/api';
const props = defineProps<{ conversationUuid?: string }>();
interface TaskSummary {
  id: string; agent_key: string; parent_task_id?: string; state: string; mode: string;
  summary: string; failureReason?: string; approvalRequestNo?: string; created_at: string; updated_at: string;
}
const query = ref(''), tasks = ref<TaskSummary[]>([]), loading = ref(false), error = ref('');
const selectedConversation = computed(() => props.conversationUuid || query.value.trim());
let requestVersion = 0;
async function load() {
  const version = ++requestVersion;
  tasks.value = []; error.value = '';
  if (!selectedConversation.value) return;
  loading.value = true;
  try {
    const response = await api.get('/agent/a2a/tasks', { params: { conversationUuid: selectedConversation.value } });
    if (version === requestVersion) tasks.value = response.data || [];
  } catch { if (version === requestVersion) error.value = '通信任务加载失败，请重试。'; }
  finally { if (version === requestVersion) loading.value = false; }
}
watch(() => props.conversationUuid, () => { void load(); }, { immediate: true });
</script>
<style scoped>
.a2a-panel { margin: 16px 0; }
.task-timeline { margin-top: 24px; }
.task-meta { color: #667085; font-size: 12px; overflow-wrap: anywhere; }
</style>
