// Glue between the Elm app and autonomous.
//
// The UI's agent tools are registered here. Each one becomes a message on
// Elm's `agentCommand` port, runs through Elm's `update` like a click, and
// resolves with the UI state Elm sends back on `agentReply`. The page then
// dials the bridge, so agents reach this tab as the target "frontend".
import * as autonomous from './autonomous.js';

const app = Elm.Main.init({ node: document.getElementById('app') });

// ---- Elm round trip ------------------------------------------------------
let nextId = 1;
const waiting = new Map();

app.ports.agentReply.subscribe((reply) => {
  const w = waiting.get(reply.id);
  if (!w) return;
  waiting.delete(reply.id);
  reply.error ? w.reject(new Error(reply.error)) : w.resolve(reply.ok);
});

function ui(tool, args = {}) {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    waiting.set(id, { resolve, reject });
    app.ports.agentCommand.send({ id, tool, args });
  });
}

// ---- the backend's change stream -------------------------------------------
const events = new EventSource('/api/events');
events.onmessage = (e) => app.ports.serverEvent.send(JSON.parse(e.data));

// ---- the UI as agent tools --------------------------------------------------
autonomous.setApp({
  name: 'todo-frontend',
  description:
    'The Elm UI a human is looking at. ui_state reads what they see (filter, draft, visible todos). ' +
    'ui_type, ui_submit, ui_set_filter and ui_click_* act through the UI exactly as a click or a keystroke ' +
    'does, so the human watches it happen. The data itself lives in the backend app.',
});

const read = { input: '[:map]', readOnly: true };
const tools = [
  ['ui/state', { ...read, description: 'What the human sees: the filter, the draft in the input, the visible todos, how many are left.' }],
  ['ui/type', { description: 'Type into the new-todo input, replacing its text.', input: '[:map [:text :string]]' }],
  ['ui/submit', { description: 'Press Enter in the new-todo input: adds the draft as a todo.', input: '[:map]' }],
  ['ui/set_filter', { description: 'Click a filter: all, active or done.', input: '[:map [:filter [:enum "all" "active" "done"]]]' }],
  ['ui/click_toggle', { description: "Click a todo's checkbox.", input: '[:map [:id :int]]' }],
  ['ui/click_remove', { description: "Click a todo's delete button.", input: '[:map [:id :int]]' }],
  ['ui/click_clear_done', { description: 'Click "Clear done".', input: '[:map]' }],
];

for (const [id, spec] of tools) {
  const command = id.slice('ui/'.length);
  autonomous.registerTool(id, spec, (args) => ui(command, args));
}

// ---- a public playground: tell visitors how to connect their agent ------------
function showPlayground({ mcpUrl, agentToken, resetSecs, maxTodos, maxText }) {
  const minutes = Math.round(resetSecs / 60);
  document.getElementById('pg-about').textContent =
    `This list lives in a Rust backend. Connect your own agent over MCP and it gets the backend's ` +
    `tools (backend__todos_list, backend__todos_add, ...): what it does shows up here at once, ` +
    `and what you do here lands in its journal. At most ${maxTodos} todos of ${maxText} characters; ` +
    (minutes > 0 ? `the list resets every ${minutes} minutes.` : 'the list never resets.');
  document.getElementById('pg-claude').textContent =
    `claude mcp add --transport http autonomous-playground ${mcpUrl} ` +
    `--header "Authorization: Bearer ${agentToken}"`;
  document.getElementById('pg-json').textContent = JSON.stringify(
    {
      mcpServers: {
        'autonomous-playground': {
          type: 'http',
          url: mcpUrl,
          headers: { Authorization: `Bearer ${agentToken}` },
        },
      },
    },
    null,
    2,
  );
  for (const b of document.querySelectorAll('[data-copy]')) {
    b.addEventListener('click', () => {
      navigator.clipboard?.writeText(document.getElementById(b.dataset.copy).textContent);
      b.textContent = 'copied';
      setTimeout(() => (b.textContent = 'copy'), 1500);
    });
  }
  document.getElementById('playground').hidden = false;
}

// ---- dial the bridge ---------------------------------------------------------
const status = document.getElementById('agent-status');
fetch('/config.json')
  .then((r) => r.json())
  .then(({ wsUrl, token, playground }) => {
    if (playground) showPlayground(playground);
    if (!token) {
      status.textContent = playground
        ? 'agents reach the backend; this page is not an agent target here'
        : 'agents: off (no frontend token)';
      return;
    }
    autonomous.connect({
      url: wsUrl,
      token,
      runtimeId: 'tab-' + Math.random().toString(36).slice(2, 8),
      label: navigator.userAgent.includes('Headless') ? 'headless tab' : 'browser tab',
      onStatus: (s) => {
        status.textContent = 'agents: ' + s;
        status.dataset.status = s;
      },
    });
  });
