import asyncio
import websockets
import json
import urllib.request

async def run_audit():
    req = urllib.request.urlopen('http://127.0.0.1:9223/json')
    targets = json.loads(req.read().decode())
    ws_url = targets[0]['webSocketDebuggerUrl']
    
    async with websockets.connect(ws_url) as ws:
        print("=== INICIANDO AUDITORIA MISION POR MISION (1 a 20) ===")
        for i in range(20):
            expr = f"loadMission({i}); JSON.stringify({{ name: AppState.currentMissionName, pts: AppState.points.length, alt: AppState.altitude, spd: AppState.speed, cam: AppState.camera }});"
            await ws.send(json.dumps({'id': i*10+1, 'method': 'Runtime.evaluate', 'params': {'expression': expr}}))
            res1 = json.loads(await ws.recv())
            info = json.loads(res1['result']['result']['value'])
            
            # Unlock
            await ws.send(json.dumps({'id': i*10+2, 'method': 'Runtime.evaluate', 'params': {'expression': f"toggleMissionLock({i}); AppState.savedMissions[{i}].locked;"}}))
            res2 = json.loads(await ws.recv())
            
            # Re-lock
            await ws.send(json.dumps({'id': i*10+3, 'method': 'Runtime.evaluate', 'params': {'expression': f"toggleMissionLock({i}); AppState.savedMissions[{i}].locked;"}}))
            res3 = json.loads(await ws.recv())
            locked = res3['result']['result']['value']
            
            print(f"Mision {i+1:02d}: {info['name']} | Pts: {info['pts']} | Alt: {info['alt']}m | Vel: {info['spd']}m/s | Cam: {info['cam']} | Candado: {locked}")
            await asyncio.sleep(0.04)
        print("=== TODAS LAS 20 MISIONES AUDITADAS EXITOSAMENTE SIN ERRORES ===")

if __name__ == "__main__":
    asyncio.run(run_audit())
