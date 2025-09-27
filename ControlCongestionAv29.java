import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.*;

/**
 * Control de congestión para la Avenida 29 - Zona crítica del cruce (1,29)
 * 
 * Este sistema implementa un mecanismo de prioridad temporal que:
 * - Detecta congestión en las posiciones [2-5][29]
 * - Otorga ventanas de prioridad limitadas a robots verdes cuando hay azules esperando
 * - Evita deadlocks mediante timeouts y control de cupos
 */
public final class ControlCongestionAv29 {
    
    // ---- SINCRONIZACIÓN
    /** Monitor para control de congestión - protege el estado compartido mediante exclusión mutua */
    private final ReentrantLock lock = new ReentrantLock(true);
    
    /** Condición que permite a los robots azules esperar hasta poder avanzar */
    private final Condition azulPuedeAvanzar = lock.newCondition();
    
    // --- ESTADO DE LA VENTANA DE PRIORIDAD ---
    /** Indica si actualmente hay una ventana de prioridad verde activa */
    private volatile boolean prioridadVerdeActiva = false;
    
    /** Número de robots verdes que están actualmente transitando la zona */
    private volatile int verdesPasando = 0;
    
    /** Número de robots verdes que deberían pasar en esta ventana */
    private volatile int verdesEsperados = 0;

    // --- CONFIGURACIÓN DE VENTANA ---
    /** Semáforo que controla los cupos disponibles en la ventana de prioridad */
    private final Semaphore cuposVentana = new Semaphore(0, true);
    
    /** Máximo número de robots verdes que pueden pasar por ventana */
    private final int MAX_POR_VENTANA = 2;
    
    /** Duración máxima de una ventana de prioridad en milisegundos */
    private final long DURACION_VENTANA_MS = 3000;
    
    /** Timestamp hasta cuando la ventana actual permanece abierta */
    private long ventanaAbiertaHasta = 0L;

    /**
     * Evalúa si debe aplicarse prioridad en la Avenida 29
     * 
     * Condiciones requeridas:
     * 1. Congestión en AV29: posiciones [2-5][29] todas ocupadas
     * 2. Robot azul esperando: en posiciones [1][24] o [1][25]
     * 
     * @return true si se debe activar la prioridad verde
     */
    public boolean debeAplicarsePrioridadAv29() {
        // Verificar congestión completa en avenida 29 (posiciones 2,29 a 5,29)
        boolean congestionAv29 =
            CarreteraPareYSiga.ocupacion[5][29].availablePermits() == 0 &&
            CarreteraPareYSiga.ocupacion[4][29].availablePermits() == 0 &&
            CarreteraPareYSiga.ocupacion[3][29].availablePermits() == 0 &&
            CarreteraPareYSiga.ocupacion[2][29].availablePermits() == 0;
            
        // Verificar que hay robot azul esperando en la aproximación
        boolean azulEsperando =
            CarreteraPareYSiga.ocupacion[1][25].availablePermits() == 0 ||
            CarreteraPareYSiga.ocupacion[1][24].availablePermits() == 0;
            
        return congestionAv29 && azulEsperando;
    }

    /**
     * Método llamado por robots azules antes de intentar pasar por (1,29)
     * 
     * El robot azul esperará si:
     * - Hay congestión en AV29
     * - Y (hay prioridad verde activa O verdes pasando O cupos disponibles)
     * 
     * Incluye timeout de seguridad para evitar bloqueos indefinidos
     * 
     * @throws InterruptedException si el hilo es interrumpido mientras espera
     */
    public void solicitarPasoAzul() throws InterruptedException {
        lock.lock();
        try {
            long inicio = System.currentTimeMillis();
            long timeoutMs = 10000; // Timeout de 10 segundos
            
            // Esperar mientras haya prioridad verde activa
            while (debeAplicarsePrioridadAv29() &&
                  (prioridadVerdeActiva || verdesPasando > 0 || cuposVentana.availablePermits() > 0)) {
                
                // Verificar timeout para evitar bloqueos indefinidos
                long dt = System.currentTimeMillis() - inicio;
                if (dt > timeoutMs) {
                    System.out.println("TIMEOUT: Azul en (1,25) forzando cierre de ventana");
                    // Forzar cierre de ventana por timeout
                    prioridadVerdeActiva = false;
                    verdesPasando = 0;
                    verdesEsperados = 0;
                    cuposVentana.drainPermits(); // Eliminar cupos restantes
                    break;
                }
                
                // Esperar con timeout parcial de 1 segundo
                azulPuedeAvanzar.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } finally { 
            lock.unlock(); 
        }
    }

    /**
     * Inicia una nueva ventana de prioridad para robots verdes
     * 
     * Solo se ejecuta si:
     * - Se cumple la condición de prioridad
     * - No hay una ventana ya activa
     * 
     * Calcula dinámicamente cuántos verdes pueden pasar basándose en
     * los robots presentes en posiciones [2][29] y [3][29]
     */
    public void iniciarPrioridadVerde() {
        lock.lock();
        try {
            // No iniciar si no se debe aplicar prioridad o ya está activa
            if (!debeAplicarsePrioridadAv29() || prioridadVerdeActiva) return;

            // Activar ventana de prioridad
            prioridadVerdeActiva = true;
            verdesPasando = 0;

            // Contar robots verdes presentes en posiciones críticas
            int presentes = 0;
            if (CarreteraPareYSiga.ocupacion[2][29].availablePermits() == 0) presentes++;
            if (CarreteraPareYSiga.ocupacion[3][29].availablePermits() == 0) presentes++;

            // Determinar cuántos verdes pueden pasar (máximo 2 por ventana)
            verdesEsperados = Math.min(MAX_POR_VENTANA, presentes);
            
            // Resetear y configurar cupos de la ventana
            cuposVentana.drainPermits(); // Limpiar cupos anteriores
            if (verdesEsperados > 0) {
                cuposVentana.release(verdesEsperados); // Otorgar nuevos cupos
            }

            // Establecer tiempo límite de la ventana
            ventanaAbiertaHasta = System.currentTimeMillis() + DURACION_VENTANA_MS;
            
            System.out.println("AV29: ventana prioridad VERDE abierta con cupos=" + verdesEsperados);
        } finally { 
            lock.unlock(); 
        }
    }

    /**
     * Método llamado por robots verdes para intentar obtener un cupo de la ventana
     * 
     * @return true si el robot puede pasar (no hay prioridad O obtuvo cupo)
     *         false si la ventana expiró y no pudo obtener cupo
     * @throws InterruptedException si es interrumpido mientras espera el cupo
     */
    public boolean tomarCupoVerde() throws InterruptedException {
        lock.lock();
        try {
            // Si no hay prioridad activa, el verde puede pasar normalmente
            if (!prioridadVerdeActiva) return true;
            
            // Verificar si la ventana ha expirado
            long restante = ventanaAbiertaHasta - System.currentTimeMillis();
            if (restante <= 0 && cuposVentana.availablePermits() == 0) {
                // Ventana expirada y sin cupos: cerrar ventana
                prioridadVerdeActiva = false;
                verdesEsperados = 0;
                azulPuedeAvanzar.signalAll(); // Despertar azules esperando
                return false;
            }
        } finally { 
            lock.unlock(); 
        }
        
        // Intentar obtener un cupo con timeout de 1.2 segundos
        return cuposVentana.tryAcquire(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Verifica si la ventana debe cerrarse y la cierra si corresponde
     * 
     * Condiciones para cerrar:
     * - No hay verdes pasando (verdesPasando == 0)
     * - No quedan cupos disponibles
     * - La prioridad está activa
     */
    private void cerrarVentanaSiCorresponde() {
        if (verdesPasando == 0 && cuposVentana.availablePermits() == 0 && prioridadVerdeActiva) {
            // Cerrar ventana: todos los verdes pasaron y no hay más cupos
            prioridadVerdeActiva = false;
            verdesEsperados = 0;
            azulPuedeAvanzar.signalAll(); // Notificar a azules que pueden avanzar
            System.out.println("AV29: ventana cerrada; cediendo a AZUL");
        }
    }

    /**
     * Registra que un robot verde ha comenzado a transitar la zona crítica
     * 
     * Llamado cuando un verde con prioridad entra a la zona [2-5][29]
     */
    public void registrarVerdeEnTransito() {
        lock.lock();
        try {
            verdesPasando++;
            System.out.println("Robot verde en tránsito. Pasando: " + verdesPasando + 
                             " de " + verdesEsperados + " esperados");
        } finally { 
            lock.unlock(); 
        }
    }

    /**
     * Registra que un robot verde ha salido de la zona crítica
     * 
     * Llamado cuando un verde termina de transitar la zona [2-5][29]
     * Puede desencadenar el cierre de la ventana si era el último verde
     */
    public void registrarVerdeSalio() {
        lock.lock();
        try {
            if (verdesPasando > 0) {
                verdesPasando--;
            }
            // Verificar si la ventana debe cerrarse
            cerrarVentanaSiCorresponde();
        } finally { 
            lock.unlock(); 
}
}
}
