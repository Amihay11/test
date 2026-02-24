const agents = [
  { name: 'Scout', role: 'Discovery Analyst' },
  { name: 'Atlas', role: 'Planning Strategist' },
  { name: 'Forge', role: 'Implementation Engineer' },
  { name: 'Sentinel', role: 'QA Guardian' },
  { name: 'Pulse', role: 'Performance Optimizer' },
  { name: 'Beacon', role: 'Release Coordinator' }
];

const columns = ['Backlog', 'In Progress', 'Review', 'Done'];
let simulationStep = 0;
let throughput = 18;
let quality = 78;
let cycle = 45;
let blocked = 3;

const trends = [];

const boardColumns = document.getElementById('boardColumns');
const agentGrid = document.getElementById('agentGrid');
const decisionLog = document.getElementById('decisionLog');
const tpl = document.getElementById('agentTemplate');

function renderBoard() {
  boardColumns.innerHTML = '';
  columns.forEach((column, idx) => {
    const wrapper = document.createElement('section');
    wrapper.className = 'column';
    wrapper.innerHTML = `<h3>${column}</h3>`;
    const itemCount = Math.max(1, 4 - Math.abs(simulationStep - idx));
    for (let i = 0; i < itemCount; i += 1) {
      const card = document.createElement('div');
      card.className = 'ticket';
      card.textContent = `${column} · Task ${idx + 1}.${i + 1} - autonomous workflow`;
      wrapper.appendChild(card);
    }
    boardColumns.appendChild(wrapper);
  });
}

function renderAgents() {
  agentGrid.innerHTML = '';
  agents.forEach((agent, index) => {
    const clone = tpl.content.cloneNode(true);
    clone.querySelector('h3').textContent = agent.name;
    clone.querySelector('.role').textContent = agent.role;
    const progress = Math.min(100, (simulationStep * 12 + index * 11) % 101);
    clone.querySelector('.status').textContent = progress > 85 ? 'Delivering insights' : 'Executing mission';
    clone.querySelector('.progress span').style.width = `${progress}%`;
    agentGrid.appendChild(clone);
  });
}

function addLog(message) {
  const li = document.createElement('li');
  li.textContent = `${new Date().toLocaleTimeString()} — ${message}`;
  decisionLog.prepend(li);
  while (decisionLog.children.length > 10) {
    decisionLog.lastChild.remove();
  }
}

function updateMetrics() {
  throughput += Math.floor(Math.random() * 4);
  quality = Math.min(99, quality + Math.floor(Math.random() * 3));
  cycle = Math.max(12, cycle - Math.floor(Math.random() * 4));
  blocked = Math.max(0, blocked - (Math.random() > 0.45 ? 1 : 0));

  document.getElementById('throughput').textContent = `${throughput} tasks/hr`;
  document.getElementById('quality').textContent = `${quality}%`;
  document.getElementById('cycle').textContent = `${cycle} min`;
  document.getElementById('blocked').textContent = `${blocked}`;

  trends.push({ throughput, quality });
  if (trends.length > 16) trends.shift();
  drawChart();
}

function drawChart() {
  const canvas = document.getElementById('trendChart');
  const ctx = canvas.getContext('2d');
  const w = canvas.width;
  const h = canvas.height;

  ctx.clearRect(0, 0, w, h);
  ctx.strokeStyle = '#2a426d';
  ctx.lineWidth = 1;
  for (let y = 20; y < h; y += 40) {
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(w, y);
    ctx.stroke();
  }

  const drawLine = (key, color, maxVal) => {
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.beginPath();
    trends.forEach((point, i) => {
      const x = (i / Math.max(1, trends.length - 1)) * (w - 20) + 10;
      const y = h - (point[key] / maxVal) * (h - 30) - 10;
      i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
    });
    ctx.stroke();
  };

  drawLine('throughput', '#57d6ff', 60);
  drawLine('quality', '#57ff9e', 100);
}

function runCycle() {
  simulationStep = (simulationStep + 1) % 5;
  renderBoard();
  renderAgents();
  updateMetrics();
  addLog('Agents synchronized a new collaboration cycle and reduced delivery risk.');
}

function resetAll() {
  simulationStep = 0;
  throughput = 18;
  quality = 78;
  cycle = 45;
  blocked = 3;
  trends.length = 0;
  renderBoard();
  renderAgents();
  updateMetrics();
  decisionLog.innerHTML = '';
  addLog('System reset complete. Standing by for mission launch.');
}

document.getElementById('runCycleBtn').addEventListener('click', runCycle);
document.getElementById('resetBtn').addEventListener('click', resetAll);

resetAll();
setInterval(() => {
  if (document.visibilityState === 'visible') {
    runCycle();
  }
}, 4500);
