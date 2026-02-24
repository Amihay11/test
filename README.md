# Nexus Ops — Localhost Web App

An impressive single-page web app that simulates a **multi-agent software delivery control center**.

## What it includes

- Real-time dashboard metrics (throughput, quality score, cycle time, blocked items)
- Animated agent swarm cards with progress bars
- Kanban-style delivery board with dynamic task distribution
- Decision log with recent collaboration events
- Canvas-based trend visualization updated during simulation cycles

## Run locally

Because this is a browser app, start a local server from this folder:

```bash
python -m http.server 8000
```

Then open:

- `http://localhost:8000`

## Files

- `index.html` — main app shell
- `styles.css` — visual theme and responsive layout
- `app.js` — simulation engine, board updates, metrics, chart drawing

## Notes

- No external dependencies are required.
- Works fully offline on localhost.
