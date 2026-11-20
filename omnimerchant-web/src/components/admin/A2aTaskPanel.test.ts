import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import A2aTaskPanel from './A2aTaskPanel.vue';
import api from '@/api';
vi.mock('@/api', () => ({ default: { get: vi.fn() } }));
describe('A2aTaskPanel', () => {
  it('loads conversation tasks and shows approval as pending review', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: [{ id: 'task-1', agent_key: 'after-sales',
      state: 'TASK_STATE_COMPLETED', mode: 'FIXTURE', summary: '已提交内部申请', approvalRequestNo: 'ACT-001' }] });
    const wrapper = mount(A2aTaskPanel, { props: { conversationUuid: 'conversation-1' } });
    await flushPromises();
    expect(api.get).toHaveBeenCalledWith('/agent/a2a/tasks', { params: { conversationUuid: 'conversation-1' } });
    expect(wrapper.text()).toContain('ACT-001');
    expect(wrapper.text()).toContain('申请提交不代表退款已执行');
    expect(wrapper.text()).toContain('FIXTURE');
  });
});
