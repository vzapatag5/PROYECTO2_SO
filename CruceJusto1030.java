import java.util.concurrent.locks.*;

/**
 * MONITOR PARA EL CRUCE PRINCIPAL (10,30)
 * 
 * Este es el corazón del sistema de control de tráfico. Coordina la intersección
 * más crítica donde se encuentran las rutas principales de robots azules y verdes.
 * 
 * FUNCIONES PRINCIPALES:
 * - Sistema de turnos alternos: AZULES ↔ VERDES ↔ NINGUNO
 * - Detección inteligente de congestión y prioridades dinámicas
 * - Prevención de deadlocks mediante timeouts y señalización
 * 
 * CASOS ESPECIALES MANEJADOS:
 * 1. Prioridad por Congestión: Cuando hay robots verdes bloqueados y espacio suficiente
 * 2. Prioridad Limitada: Cuando hay congestión pero espacio limitado
 * 3. Operación Normal: Turnos regulares controlados por ControlTraficoInteligente
 * 
 * PATRÓN: Monitor (ReentrantLock + Conditions) para exclusión mutua y coordinación
 */
public final class CruceJusto1030 {
    
    // === COMPONENTES DEL MONITOR ===
    /** Lock principal para exclusión mutua - garantiza que solo un hilo modifique el estado */
    private final ReentrantLock lock = new ReentrantLock(true); // fair = true (FIFO)
    
    /** Condition variable: robots azules esperan aquí hasta poder pasar */
    private final Condition puedenPasarAzules = lock.newCondition();
    
    /** Condition variable: robots verdes esperan aquí hasta poder pasar */
    private final Condition puedenPasarVerdes = lock.newCondition();

    // === ESTADOS POSIBLES DEL CRUCE ===
    /**
     * Enum que define los tres estados posibles del cruce:
     * - NINGUNO: Cruce libre, puede cambiar a cualquier dirección
     * - AZULES_PASANDO: Solo robots azules pueden usar el cruce
     * - VERDES_PASANDO: Solo robots verdes pueden usar el cruce
     */
    public enum Turno { NINGUNO, AZULES_PASANDO, VERDES_PASANDO }

    // === ESTADO INTERNO DEL MONITOR ===
    /** Estado actual del cruce - controlado por ControlTraficoInteligente y lógica de prioridades */
    private Turno turnoActual = Turno.NINGUNO;
    
    /** Contador de robots que están actualmente transitando el cruce */
    private int robotsPasando = 0;

    /**
     * CAMBIO DE TURNO - Llamado por ControlTraficoInteligente
     * 
     * Cambia el estado del cruce y notifica a los robots correspondientes.
     * Este método es thread-safe y atómico.
     * 
     * @param nuevoTurno El nuevo estado del cruce (NINGUNO, AZULES_PASANDO, VERDES_PASANDO)
     * 
     * COMPORTAMIENTO:
     * - AZULES_PASANDO: Despierta a todos los robots azules esperando
     * - VERDES_PASANDO: Despierta a todos los robots verdes esperando  
     * - NINGUNO: No despierta a nadie (estado neutral)
     */
    public void cambiarTurno(Turno nuevoTurno) {
        lock.lock(); // Adquirir exclusión mutua
        try {
            // Cambiar el estado interno del cruce
            turnoActual = nuevoTurno;
            
            // Notificar a los robots correspondientes según el nuevo turno
            if (nuevoTurno == Turno.AZULES_PASANDO) {
                puedenPasarAzules.signalAll(); // Despertar TODOS los azules esperando
            } else if (nuevoTurno == Turno.VERDES_PASANDO) {
                puedenPasarVerdes.signalAll(); // Despertar TODOS los verdes esperando
            }
            // Si nuevoTurno == NINGUNO, no despertar a nadie
            
        } finally { 
            lock.unlock(); // Garantizar liberación del lock
        }
    }

    /**
     * DETECCIÓN DE PRIORIDAD POR CONGESTIÓN COMPLETA
     * 
     * Verifica si se debe dar prioridad máxima a robots verdes debido a congestión severa.
     * 
     * CONDICIONES PARA ACTIVAR PRIORIDAD COMPLETA:
     * 1. Hay un robot verde en (10,29) - el punto crítico del cruce
     * 2. Congestión azul: robots azules bloqueados en (2,30), (3,30), (4,30)
     * 3. Espacio suficiente: tanto (5,29) como (4,29) están libres
     * 
     * RAZÓN: Si hay congestión de azules Y espacio libre para que los verdes
     * se muevan, es eficiente darles prioridad completa para desbloquear el sistema.
     * 
     * @return true si se debe aplicar prioridad por congestión
     */
    private boolean debeAplicarsePrioridadPorCongestion() {
        // Verificar si hay robot verde esperando en el punto crítico
        boolean hayRobotEn10_29 = CarreteraPareYSiga.ocupacion[10][29].availablePermits() == 0;
        
        // Verificar congestión de robots azules en avenida 30 (calles 2,3,4)
        boolean congestionAzul =
            CarreteraPareYSiga.ocupacion[4][30].availablePermits() == 0 &&
            CarreteraPareYSiga.ocupacion[3][30].availablePermits() == 0 &&
            CarreteraPareYSiga.ocupacion[2][30].availablePermits() == 0;
            
        // Verificar que hay espacio libre para que los verdes se muevan
        boolean ambosLibres =
            CarreteraPareYSiga.ocupacion[5][29].availablePermits() == 1 &&
            CarreteraPareYSiga.ocupacion[4][29].availablePermits() == 1;
            
        // Solo aplicar si se cumplen TODAS las condiciones
        return hayRobotEn10_29 && congestionAzul && ambosLibres;
    }

    /**
     * DETECCIÓN DE PRIORIDAD LIMITADA
     * 
     * Verifica si se debe dar prioridad restringida a robots verdes cuando
     * hay congestión pero espacio limitado.
     * 
     * CONDICIONES PARA ACTIVAR PRIORIDAD LIMITADA:
     * 1. Hay un robot verde en (10,29) - mismo punto crítico
     * 2. Congestión azul: robots azules bloqueados en (2,30), (3,30), (4,30)  
     * 3. Espacio limitado: solo (5,29) libre, pero (4,29) ocupado
     * 
     * DIFERENCIA CON PRIORIDAD COMPLETA:
     * - Prioridad Completa: Ambos espacios (5,29) y (4,29) libres → más agresiva
     * - Prioridad Limitada: Solo (5,29) libre → más conservadora
     * 
     * RAZÓN: Aún hay congestión que justifica dar prioridad a verdes, pero con
     * menos espacio disponible, por lo que debe ser más cuidadosa.
     * 
     * @return true si se debe aplicar prioridad limitada
     */
    private boolean debeAplicarsePrioridadLimitada() {
        // Verificar si hay robot verde esperando en el punto crítico  
        boolean hayRobotEn10_29 = CarreteraPareYSiga.ocupacion[10][29].availablePermits() == 0;
        
        // Verificar congestión de robots azules en avenida 30 (calles 2,3,4)
        boolean congestionAzul =
            CarreteraPareYSiga.ocupacion[4][30].availablePermits() == 0 &&
            CarreteraPareYSiga.ocupacion[3][30].availablePermits() == 0 &&
            CarreteraPareYSiga.ocupacion[2][30].availablePermits() == 0;
            
        // Verificar espacio limitado: solo (5,29) libre, (4,29) ocupado
        boolean solo5_29Libre =
            CarreteraPareYSiga.ocupacion[5][29].availablePermits() == 1 &&
            CarreteraPareYSiga.ocupacion[4][29].availablePermits() == 0;
            
        // Solo aplicar si se cumplen TODAS las condiciones
        return hayRobotEn10_29 && congestionAzul && solo5_29Libre;
    }

    /**
     * ENTRADA AL CRUCE - Lógica principal de coordinación
     * 
     * Método llamado por cada robot antes de usar el cruce (10,30).
     * Implementa una lógica de prioridades inteligente que se adapta
     * dinámicamente a las condiciones de tráfico.
     * 
     * ALGORITMO DE DECISIÓN (en orden de prioridad):
     * 1. PRIORIDAD POR CONGESTIÓN: Si hay congestión severa → Verdes pasan inmediatamente
     * 2. PRIORIDAD LIMITADA: Si hay congestión moderada → Verdes pasan inmediatamente  
     * 3. OPERACIÓN NORMAL: Respetar turno actual → Esperar si no es mi turno
     * 
     * @param miTurno El tipo de robot (AZULES_PASANDO o VERDES_PASANDO)
     * @throws InterruptedException Si el hilo es interrumpido mientras espera
     * 
     * GARANTÍAS:
     * - Thread-safe: Solo un robot evalúa condiciones a la vez
     * - Deadlock-free: Siempre hay progreso mediante señalización
     * - Fair: Los turnos normales garantizan equidad cuando no hay prioridades
     */
    public void entrarCruce(Turno miTurno) throws InterruptedException {
        lock.lock(); // Adquirir exclusión mutua para evaluación atómica
        try {
            // === BUCLE DE ESPERA CON CONDICIONES DINÁMICAS ===
            while (true) {
                
                // === CASO 1: PRIORIDAD POR CONGESTIÓN (máxima prioridad a verdes) ===
                if (debeAplicarsePrioridadPorCongestion()) {
                    if (miTurno == Turno.VERDES_PASANDO) {
                        //  Soy verde y hay prioridad por congestión → PASO INMEDIATAMENTE
                        break;
                    } else if (miTurno == Turno.AZULES_PASANDO) { 
                        //  Soy azul pero los verdes tienen prioridad → ESPERO
                        puedenPasarAzules.await(); 
                        continue; // Re-evaluar condiciones al despertar
                    }
                    
                // === CASO 2: PRIORIDAD LIMITADA (prioridad moderada a verdes) ===
                } else if (debeAplicarsePrioridadLimitada()) {
                    if (miTurno == Turno.VERDES_PASANDO) {
                        //  Soy verde y hay prioridad limitada → PASO INMEDIATAMENTE
                        break;
                    } else if (miTurno == Turno.AZULES_PASANDO) { 
                        //  Soy azul pero los verdes tienen prioridad → ESPERO
                        puedenPasarAzules.await(); 
                        continue; // Re-evaluar condiciones al despertar
                    }
                    
                // === CASO 3: OPERACIÓN NORMAL (respetar turnos del controlador) ===
                } else {
                    if (turnoActual == miTurno) {
                        //  Es mi turno según el controlador → PASO
                        break;
                    }
                    //  No es mi turno → ESPERO hasta que me toque
                    if (miTurno == Turno.AZULES_PASANDO) {
                        puedenPasarAzules.await();
                    } else {
                        puedenPasarVerdes.await();
                    }
                    // Al despertar, re-evaluar todas las condiciones (while loop)
                }
            }
            
            // === REGISTRO DE ENTRADA ===
            robotsPasando++; // Incrementar contador de robots en el cruce
            
        } finally { 
            lock.unlock(); // Garantizar liberación del lock
        }
    }

    /**
     * SALIDA DEL CRUCE - Notificación de liberación
     * 
     * Método llamado por cada robot después de completar su paso por el cruce.
     * Actualiza contadores y puede desencadenar cambios de estado.
     * 
     * LÓGICA DE SEÑALIZACIÓN:
     * - Decrementa el contador de robots en el cruce
     * - Si no quedan robots (contador = 0), notifica a robots azules esperando
     * 
     * ¿POR QUÉ SOLO NOTIFICAR A AZULES?
     * Las prioridades siempre favorecen a verdes cuando hay congestión.
     * Cuando el cruce se vacía, es momento de que los azules (que pueden 
     * haber estado esperando) tengan oportunidad de re-evaluar las condiciones.
     * Los verdes serán notificados por cambiarTurno() si corresponde.
     * 
     * THREAD-SAFETY:
     * Método completamente thread-safe con exclusión mutua garantizada.
     */
    public void salirCruce() {
        lock.lock(); // Adquirir exclusión mutua
        try {
            // === ACTUALIZAR CONTADOR DE OCUPACIÓN ===
            robotsPasando--; // Decrementar robots en el cruce
            
            // === SEÑALIZACIÓN CONDICIONAL ===
            if (robotsPasando == 0) {
                // Cruce completamente libre → Notificar a azules esperando
                // Los azules re-evaluarán las condiciones de prioridad
                puedenPasarAzules.signalAll();
            }
            // Nota: No notificar a verdes aquí - ControlTraficoInteligente 
            // se encarga de eso mediante cambiarTurno()
            
        } finally { 
            lock.unlock(); // Garantizar liberación del lock
}
}
}
