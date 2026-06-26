# CAN A7 Electrical Telemetry

The terminal accepts an additional wrapped 8-byte payload for per-node
electrical measurements:

`A7 NODE CURRENT_LO CURRENT_HI VOLTAGE_LO VOLTAGE_HI FLAGS 00`

- `NODE`: 1-16
- Current raw unit: mA
- Voltage raw unit: 0.01 V
- Nodes 1-8: fertilizer
- Nodes 9-16: seed

This frame is optional and does not replace the existing A6 motor status frame.
Until a node sends A7, its UI electrical values remain unavailable.
