// Kept free of dependencies so the checkpoint code and its tests can load it on their own.
export const subagentType = 'openrouter:subagent';

export function discardUnfinishedSubagents(state) {
  const unfinished = new Map();
  for (const item of state.messages) {
    if (item.type === subagentType && item.callId) {
      unfinished.set(item.callId, item.status === 'in_progress');
    }
  }
  if (![...unfinished.values()].some(Boolean)) return state;

  // OpenRouter requires a result for every projected call in a suspended worker's replay.
  // The app starts a fresh turn after a stop, so drop the unfinished worker and its calls together.
  const childCalls = new Set(state.messages
    .filter(item => item.type === 'function_call' && unfinished.get(item.subagentId))
    .map(item => item.callId));
  const messages = state.messages.filter(item =>
    !(item.type === subagentType && unfinished.get(item.callId)) &&
    !((item.type === 'function_call' || item.type === 'function_call_output') && childCalls.has(item.callId)));
  const pendingToolCalls = state.pendingToolCalls?.filter(call => !childCalls.has(call.id));
  if (state.status === 'awaiting_client_tools' && pendingToolCalls?.length === 0) {
    return { ...state, messages, pendingToolCalls: undefined, status: 'complete' };
  }
  return { ...state, messages, pendingToolCalls };
}
