import java.util.concurrent.locks.*;

/**
 * SIMULADOR DE CARRIL ÚNICO (AUTOPISTA BIDIRECCIONAL)
 * 
 * Esta clase implementa un sistema de control para carreteras de un solo carril
 * donde el tráfico puede ir en ambas direcciones, pero solo una a la vez.
 * 
 * ANALOGÍA: Como un puente estrecho donde los autos deben esperar su turno
 * 
 * CASOS DE USO EN EL PROYECTO:
 * - Corredor C1 (calle 1): Para robots azules, capacidad 4
 * - Corredor C10 (calle 10): Para robots verdes, capacidad 6
 * 
 * CARACTERÍSTICAS:
 * - Control de dirección exclusiva (Este ↔ Oeste)
 * - Sistema de ventanas temporales para limitar throughput
 * - Prevención de inanición mediante señalización justa
 * - Thread-safe usando Monitor Pattern
 * 
 * VENTAJAS:
 * - Aumenta throughput permitiendo múltiples robots en la misma dirección
 * - Evita deadlocks mediante exclusión mutua por dirección
 * - Controla congestión con límites de ventana configurables
 */
public final class OneLane {
    
    // === ENUM DE DIRECCIONES ===
    /**
     * Estados posibles del carril:
     * - NONE: Carril libre, cualquier dirección puede tomarlo
     * - EAST: Carril controlado por tráfico hacia el este (robots azules típicamente)
     * - WEST: Carril controlado por tráfico hacia el oeste (robots verdes típicamente)
     */
    private enum Sentido { NONE, EAST, WEST }

    // === COMPONENTES DEL MONITOR ===
    /** Lock principal para exclusión mutua - fair=true garantiza orden FIFO */
    private final ReentrantLock lock = new ReentrantLock(true);
    
    /** Condition para robots que van hacia el ESTE - esperan aquí cuando no pueden pasar */
    private final Condition eastCanGo = lock.newCondition();
    
    /** Condition para robots que van hacia el OESTE - esperan aquí cuando no pueden pasar */
    private final Condition westCanGo = lock.newCondition();
    
    // === ESTADO DEL CARRIL ===
    /** Dirección actual que controla el carril (NONE = libre) */
    private Sentido turno = Sentido.NONE;
    
    /** Contadores de robots activos: enEste = robots yendo al este, enOeste = robots yendo al oeste */
    private int enEste = 0, enOeste = 0;
    
    // === SISTEMA DE VENTANAS TEMPORALES ===
    /** Límite máximo de robots que pueden usar el carril simultáneamente (0 = sin límite) */
    private final int cupoVentana; // 0 = sin límite
    
    /** Contador actual de robots en la "ventana" temporal */
    private int enVentana = 0;

    /**
     * CONSTRUCTOR - Configuración del carril
     * 
     * @param cupoVentana Límite de robots simultáneos en el carril
     *                    - 0: Sin límite (carril tradicional)
     *                    - 4: Máximo 4 robots (Corredor C1 para azules)
     *                    - 6: Máximo 6 robots (Corredor C10 para verdes)
     * 
     * PROPÓSITO DEL LÍMITE:
     * Evitar que demasiados robots usen el carril a la vez, lo que podría
     * causar congestión o bloquear indefinidamente a la dirección opuesta.
     */
    public OneLane(int cupoVentana) { this.cupoVentana = cupoVentana; }

    /**
     * ENTRADA AL CARRIL - Método principal de acceso
     * 
     * Los robots llaman este método para obtener permiso de usar el carril.
     * Implementa lógica de espera hasta que sea seguro proceder.
     * 
     * @param eastbound true si el robot va hacia el ESTE, false si va hacia el OESTE
     * @throws InterruptedException si el hilo es interrumpido mientras espera
     * 
     * ALGORITMO:
     * 1. Determinar mi dirección (EAST o WEST)
     * 2. Esperar hasta que pueda entrar según las reglas del carril
     * 3. Incrementar contador de mi dirección
     * 4. Si el carril estaba libre (NONE), tomar control para mi dirección
     * 
     * CONDICIONES PARA ENTRAR (ver puedoEntrar()):
     * - Carril libre (NONE) → Siempre puedo entrar
     * - Carril de mi dirección → Puedo unirme al flujo
     * - Carril opuesto pero vacío → Puedo cambiar la dirección
     */
    public void enter(boolean eastbound) throws InterruptedException {
        lock.lock(); // Adquirir exclusión mutua
        try {
            // Convertir parámetro booleano a enum para claridad
            Sentido yo = eastbound ? Sentido.EAST : Sentido.WEST;
            
            // === BUCLE DE ESPERA ===
            while (!puedoEntrar(yo)) {
                // Esperar en la condition variable correspondiente a mi dirección
                if (yo == Sentido.EAST) eastCanGo.await(); 
                else westCanGo.await();
                // Al despertar, re-evaluar si puedo entrar
            }
            
            // === REGISTRO DE ENTRADA ===
            // Incrementar contador de robots en mi dirección
            if (yo == Sentido.EAST) enEste++; 
            else enOeste++;
            
            // Si carril estaba libre, tomar control para mi dirección
            if (turno == Sentido.NONE) turno = yo;
            
        } finally { 
            lock.unlock(); // Garantizar liberación del lock
        }
    }

    /**
     * INTENTO DE ENTRADA A VENTANA TEMPORAL - Control de throughput
     * 
     * Sistema opcional para limitar cuántos robots pueden usar el carril
     * simultaneamente, creando "ventanas temporales" de capacidad limitada.
     * 
     * @return true si obtuvo un cupo en la ventana, false si está llena
     * 
     * CASOS DE USO:
     * - Corredor C1: Máximo 4 robots azules pueden formar "convoy"
     * - Corredor C10: Máximo 6 robots verdes pueden formar "convoy" 
     * 
     * LÓGICA:
     * - Si cupoVentana = 0 → Sin límite, siempre retorna true
     * - Si hay cupos disponibles → Tomar uno y retornar true
     * - Si ventana llena → Retornar false (robot debe usar ruta alternativa)
     * 
     * BENEFICIOS:
     * - Evita que el carril se sature completamente
     * - Permite que dirección opuesta tenga oportunidades
     * - Mejora la justicia del sistema
     */
    public boolean tryEnterVentana() {
        // Si no hay límite configurado, siempre permitir entrada
        if (cupoVentana == 0) return true;
        
        lock.lock(); // Adquirir exclusión mutua
        try {
            // Verificar si hay cupos disponibles en la ventana
            if (enVentana < cupoVentana) { 
                enVentana++; // Tomar un cupo
                return true; // Éxito: robot puede usar el carril
            }
            return false; // Ventana llena: robot debe buscar alternativa
        } finally { 
            lock.unlock(); // Garantizar liberación del lock
        }
    }

    /**
     * SALIDA DE VENTANA TEMPORAL - Liberación de cupo
     * 
     * Llamado por robots que completaron su uso de la "ventana temporal"
     * del carril, liberando espacio para otros robots.
     * 
     * PROPÓSITO:
     * - Liberar un cupo de la ventana temporal
     * - Notificar a robots esperando que ahora hay espacio disponible
     * - Mantener el flujo continuo del sistema
     * 
     * SEÑALIZACIÓN:
     * Notifica a AMBAS direcciones porque:
     * 1. Robots de mi dirección pueden necesitar el cupo liberado
     * 2. Si se libera suficiente espacio, dirección opuesta puede tomar control
     * 
     * CASOS DE USO:
     * - Robot azul sale del Corredor C1 → Libera cupo para otros azules
     * - Robot verde sale del Corredor C10 → Libera cupo para otros verdes
     */
    public void leaveVentana() {
        // Si no hay sistema de ventanas, no hacer nada
        if (cupoVentana == 0) return;
        
        lock.lock(); // Adquirir exclusión mutua
        try {
            // Liberar un cupo si hay alguno ocupado
            if (enVentana > 0) enVentana--;
            
            // === NOTIFICACIÓN A AMBAS DIRECCIONES ===
            // Despertar robots esperando - pueden re-evaluar si ahora pueden entrar
            eastCanGo.signalAll(); // Despertar robots que van al este
            westCanGo.signalAll(); // Despertar robots que van al oeste
            
        } finally { 
            lock.unlock(); // Garantizar liberación del lock
        }
    }

    /**
     * SALIDA DEL CARRIL - Liberación completa
     * 
     * Llamado cuando un robot termina completamente de usar el carril.
     * Actualiza contadores y puede cambiar el control del carril.
     * 
     * @param eastbound true si el robot que sale iba hacia el ESTE, false si iba al OESTE
     * 
     * LÓGICA DE LIBERACIÓN:
     * 1. Decrementar contador de mi dirección
     * 2. Si soy el último de mi dirección → Liberar control del carril (NONE)
     * 3. Si carril queda libre → Notificar a TODAS las direcciones
     * 
     * CAMBIO DE CONTROL:
     * Cuando el último robot de una dirección sale, el carril vuelve a NONE,
     * permitiendo que la dirección opuesta pueda tomar control.
     * 
     * PREVENCIÓN DE INANICIÓN:
     * Al notificar a ambas direcciones cuando el carril se libera,
     * se garantiza que ninguna dirección quede bloqueada indefinidamente.
     */
    public void exit(boolean eastbound) {
        lock.lock(); // Adquirir exclusión mutua
        try {
            // === ACTUALIZACIÓN DE CONTADORES Y CONTROL ===
            if (eastbound) { 
                // Soy robot que va al este
                if (--enEste == 0) turno = Sentido.NONE; // Si soy el último, liberar carril
            } else {           
                // Soy robot que va al oeste  
                if (--enOeste == 0) turno = Sentido.NONE; // Si soy el último, liberar carril
            }
            
            // === NOTIFICACIÓN CUANDO CARRIL SE LIBERA ===
            if (turno == Sentido.NONE) { 
                // Carril completamente libre → Ambas direcciones pueden competir por él
                eastCanGo.signalAll(); // Despertar robots esperando hacia el este
                westCanGo.signalAll(); // Despertar robots esperando hacia el oeste
            }
            // Si turno != NONE, aún hay robots de mi dirección, no notificar
            
        } finally { 
            lock.unlock(); // Garantizar liberación del lock
        }
    }

    /**
     * EVALUACIÓN DE CONDICIONES DE ENTRADA - Lógica de decisión
     * 
     * Método privado que determina si un robot puede entrar al carril
     * según el estado actual y su dirección deseada.
     * 
     * @param yo La dirección en que quiero ir (EAST o WEST)
     * @return true si puedo entrar, false si debo esperar
     * 
     * REGLAS DE ENTRADA (en orden de evaluación):
     * 
     * 1. *CARRIL LIBRE (turno == NONE)*
     *    → Siempre puedo entrar, sin importar dirección
     *    
     * 2. *MI TURNO (turno == yo)* 
     *    → Puedo unirme al flujo de mi dirección
     *    
     * 3. *DIRECCIÓN OPUESTA PERO VACÍA*
     *    → Si no hay robots activos en mi dirección, puedo "cambiar" el carril
     *    → Ejemplo: turno=WEST pero enOeste=0, entonces EAST puede tomar control
     * 
     * 4. *CASO CONTRARIO*
     *    → Hay robots activos en dirección opuesta, debo esperar
     * 
     * EJEMPLOS:
     * - turno=NONE → Cualquiera puede entrar
     * - turno=EAST, yo=EAST → Puedo unirme a los que van al este  
     * - turno=WEST, yo=EAST, enOeste=0 → Puedo cambiar dirección
     * - turno=WEST, yo=EAST, enOeste>0 → Debo esperar
     */
    private boolean puedoEntrar(Sentido yo) {
        // === CASO 1: CARRIL COMPLETAMENTE LIBRE ===
        if (turno == Sentido.NONE) return true;
        
        // === CASO 2: ES MI TURNO - UNIRSE AL FLUJO ===
        if (turno == yo) return true;
        
        // === CASO 3: DIRECCIÓN OPUESTA PERO SIN ROBOTS ACTIVOS ===
        // Puedo "robar" el control si no hay robots activos en mi dirección
        if ((yo == Sentido.EAST && enEste == 0) || (yo == Sentido.WEST && enOeste == 0)) {
            return true;
        }
        
        // === CASO 4: DEBO ESPERAR ===
        // Hay tráfico activo en dirección opuesta
        return false;
}
}
