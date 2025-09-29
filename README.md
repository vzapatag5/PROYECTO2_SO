# Sistema "Pare y Siga"

Simulación de transporte concurrente con robots azules y verdes que mueven pasajeros evitando choques y deadlocks.

## Resumen
- Mapa: 21 calles x 31 avenidas
- Pasajeros: 500 azules en (1,7) y 500 verdes en (12,23)
- Cruces críticos: (1,29) y (10,30)
- Corredores rápidos: C1 (calle 1) y C10 (calle 10)
- Control inteligente: ventanas de prioridad + turnos alternos
- Semáforos: uno por casilla (exclusión mutua)

## Archivos clave
- Principal: [CarreteraPareYSiga.java](CarreteraPareYSiga.java)
- Robots: [RobotTransporte.java](RobotTransporte.java)
- Corredores: [OneLane.java](OneLane.java)
- Cruce 10,30: [CruceJusto1030.java](CruceJusto1030.java)
- Congestión AV29: [ControlCongestionAv29.java](ControlCongestionAv29.java)
- Turnos globales: [ControlTraficoInteligente.java](ControlTraficoInteligente.java)
- Mundo: [Carretera.kwld](Carretera.kwld) 

## Requisitos
- Java 8+
- Archivo Karel: KarelJRobot.jar (ya incluido)

## Compilación
```bat
Compilar.bat
```

## Ejecución
```bat
Run.bat
```

## Seguridad
- RAII para liberar semáforos.
- Timeouts para evitar bloqueos.
- Fairness en semáforos (FIFO).

## Métricas esperadas
- Sin colisiones.
- Progreso continuo hasta agotar pasajeros.
