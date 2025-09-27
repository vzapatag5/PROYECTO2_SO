/**
 * CONTROLADOR GLOBAL DE TRÁFICO INTELIGENTE
 * 
 * Este hilo actúa como el "semáforo maestro" del sistema que coordina 
 * el flujo principal de tráfico en la intersección más crítica (10,30).
 * 
 * FUNCIONAMIENTO:
 * - Alterna turnos entre robots azules y verdes cada 5 segundos
 * - Mantiene el flujo global del sistema para evitar bloqueos masivos
 * - Se ejecuta continuamente hasta completar todas las entregas
 * 
 * PATRÓN DE TURNOS:
 * AZULES (5s) → VERDES (5s) → AZULES (5s) → VERDES (5s) → ...
 * 
 * COORDINACIÓN:
 * - Trabaja junto con ControlCongestionAv29 para casos especiales
 * - Permite que CruceJusto1030 maneje la lógica de prioridades
 * - Garantiza progreso global del sistema
 */
public class ControlTraficoInteligente extends Thread {
    
    /**
     * BUCLE PRINCIPAL DEL CONTROLADOR DE TRÁFICO
     * 
     * Ejecuta un ciclo continuo que:
     * 1. Da turno a robots azules por 5 segundos
     * 2. Da turno a robots verdes por 5 segundos  
     * 3. Repite hasta que no queden pasajeros pendientes
     * 
     * Este sistema garantiza que ambos tipos de robots tengan
     * oportunidades equitativas de usar las rutas principales,
     * evitando que uno bloquee completamente al otro.
     */
    @Override 
    public void run() {
        System.out.println(" Control de Tráfico Inteligente iniciado");
        System.out.println(" Patrón: AZULES (5s) ↔ VERDES (5s)");
        
        try {
            // === CICLO PRINCIPAL: Continuar mientras haya trabajo pendiente ===
            while (CarreteraPareYSiga.faltanAzul.get() > 0 || 
                   CarreteraPareYSiga.faltanVerde.get() > 0) {
                
                // === FASE 1: TURNO DE ROBOTS AZULES ===
                System.out.println(" TURNO AZULES - 5 segundos");
                CarreteraPareYSiga.cruce1030.cambiarTurno(CruceJusto1030.Turno.AZULES_PASANDO);
                
                // Permitir que robots azules usen el cruce durante 5 segundos
                Thread.sleep(5000);
                
                // === FASE 2: TURNO DE ROBOTS VERDES ===  
                System.out.println(" TURNO VERDES - 5 segundos");
                CarreteraPareYSiga.cruce1030.cambiarTurno(CruceJusto1030.Turno.VERDES_PASANDO);
                
                // Permitir que robots verdes usen el cruce durante 5 segundos
                Thread.sleep(5000);
                
                // === INFORMACIÓN DE PROGRESO ===
                int azulesRestantes = CarreteraPareYSiga.faltanAzul.get();
                int verdesRestantes = CarreteraPareYSiga.faltanVerde.get();
                System.out.println(" Progreso: " + azulesRestantes + " azules + " + 
                                 verdesRestantes + " verdes restantes");
            }
            
        } catch (InterruptedException e) {
            // --- MANEJO DE INTERRUPCIONES
            /**
             * Si el hilo es interrumpido (ej: cierre de aplicación):
             * 1. Restaurar el estado de interrupción del hilo
             * 2. Salir limpiamente del bucle
             */
            Thread.currentThread().interrupt();
            System.out.println("⚠ Control de tráfico interrumpido");
        }
        
        // --- FINALIZACIÓN DEL SISTEMA 
        System.out.println(" Controlador de tráfico finalizado.");
        System.out.println(" ¡Todas las entregas completadas!");
        
        // Cambiar a estado neutral al finalizar
        CarreteraPareYSiga.cruce1030.cambiarTurno(CruceJusto1030.Turno.NINGUNO);
}
}
