import kareltherobot.*;
import java.awt.Color;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * SISTEMA DE TRANSPORTE CONCURRENTE "PARE Y SIGA"
 * 
 * Simulación de un sistema de tráfico donde robots azules y verdes transportan pasajeros:
 * 
 * ROBOTS AZULES:
 * - Recogen pasajeros en (1,7) 
 * - Los entregan en (12,30)
 * - Usan ruta rápida: Calle 1 → Avenida 30
 * 
 * ROBOTS VERDES:
 * - Recogen pasajeros en (12,23)
 * - Los entregan en (1,7)  
 * - Usan ruta rápida: Calle 10 → Avenida 30 → Calle 1
 * 
 * SISTEMAS DE CONTROL:
 * - Semáforos para cada casilla (previene colisiones)
 * - Corredores de alta velocidad con ventanas de tiempo
 * - Control de congestión en zona crítica (1,29)
 * - Sistema de turnos en cruce principal (10,30)
 * 
 * META: Transportar 500 pasajeros de cada color de forma segura y eficiente
 */
public class CarreteraPareYSiga implements Directions {
    
    // === CONFIGURACIÓN DEL MAPA ===
    /** Número máximo de calles en el mapa (1-21) */
    public static final int MAX_CALLES = 21;
    
    /** Número máximo de avenidas en el mapa (1-31) */
    public static final int MAX_AVENIDAS = 31;

    // === METAS GLOBALES DEL SISTEMA DE TRANSPORTE ===
    /** Objetivo: transportar 500 pasajeros azules */
    public static final int META_AZUL = 500;
    
    /** Objetivo: transportar 500 pasajeros verdes */
    public static final int META_VERDE = 500;
    
    /** Contador thread-safe: pasajeros azules que faltan por transportar */
    public static final AtomicInteger faltanAzul  = new AtomicInteger(META_AZUL);
    
    /** Contador thread-safe: pasajeros verdes que faltan por transportar */
    public static final AtomicInteger faltanVerde = new AtomicInteger(META_VERDE);

    // === SISTEMA DE CONTROL DE OCUPACIÓN ===
    /**
     * Matriz de semáforos para controlar la ocupación de cada casilla del mapa
     * 
     * Cada casilla (calle, avenida) tiene un semáforo con 1 permiso:
     * - Permiso disponible (1) = Casilla libre
     * - Permiso tomado (0) = Casilla ocupada por un robot
     * 
     * Esto previene colisiones garantizando que solo un robot 
     * puede ocupar cada casilla en cualquier momento.
     */
    public static final Semaphore[][] ocupacion = new Semaphore[MAX_CALLES + 1][MAX_AVENIDAS + 1];
    
    static {
        // Inicializar todos los semáforos con 1 permiso (casillas libres)
        for (int i = 1; i <= MAX_CALLES; i++) {
            for (int j = 1; j <= MAX_AVENIDAS; j++) {
                ocupacion[i][j] = new Semaphore(1, true); // true = fair (FIFO)
            }
        }
    }

    // === CORREDORES DE ALTA VELOCIDAD ===
    /**
     * Corredor C1 - Ruta rápida para robots azules
     * 
     * Ubicación: Calle 1, avenidas 12-15
     * Propósito: Permite que grupos de 4 robots azules pasen rápidamente
     * Sistema: Ventanas de tiempo limitadas para evitar congestión
     */
    public static final OneLane corredorC1  = new OneLane(4); // capacidad: 4 robots
    
    /**
     * Corredor C10 - Ruta rápida para robots verdes  
     * 
     * Ubicación: Calle 10, avenidas 24-29
     * Propósito: Permite que grupos de 6 robots verdes pasen rápidamente
     * Sistema: Ventanas de tiempo limitadas para evitar congestión
     */
    public static final OneLane corredorC10 = new OneLane(6); // capacidad: 6 robots

    // === CRUCES ESPECIALES Y CONTADORES DE TRÁFICO ===
    
    /**
     * Cruce (5,30) - Zona de conflicto en Avenida 30
     * Coordina el paso entre robots azules (subiendo) y verdes (bajando)
     */
    public static final Semaphore cruce_5_30  = new Semaphore(1, true);
    
    /** Contador: robots verdes que están bajando por Avenida 30 */
    public static final AtomicInteger verdesBajandoAv30 = new AtomicInteger(0);
    
    /** Permiso especial para que robots azules pasen por (5,30) cuando hay verdes */
    public static final Semaphore permisoAzulEn_5_30 = new Semaphore(0, true);

    /**
     * Cruce (1,29) - ZONA MÁS CRÍTICA DEL SISTEMA
     * Intersección entre ruta azul (calle 1) y ruta verde (avenida 29)
     * Controlado por el sistema de ventanas de prioridad (ControlCongestionAv29)
     */
    public static final Semaphore cruce_1_29  = new Semaphore(1, true);
    
    /** Contador: robots azules aproximándose a la zona crítica (1,29) */
    public static final AtomicInteger azulesAproximando129 = new AtomicInteger(0);
    
    /** Cruce (1,26) - Control de acceso a zona crítica */
    public static final Semaphore cruce_1_26  = new Semaphore(1, true);
    
    /** Cruce (1,21) - Punto de control en corredor C1 */
    public static final Semaphore cruce_1_21  = new Semaphore(1, true);
    
    /** Cruce (1,16) - Entrada/salida del corredor C1 */
    public static final Semaphore cruce_1_16  = new Semaphore(1, true);

    // === SISTEMAS INTELIGENTES DE CONTROL DE TRÁFICO ===
    
    /**
     * Control de Congestión AV29 - Sistema de ventanas de prioridad
     * 
     * Funciones:
     * - Detecta congestión en avenida 29 (robots verdes bloqueados)
     * - Otorga ventanas temporales de prioridad a robots verdes
     * - Coordina con robots azules para evitar deadlocks
     * - Implementa timeouts de seguridad
     */
    public static final ControlCongestionAv29 controlAv29 = new ControlCongestionAv29();
    
    /**
     * Cruce 1030 - Monitor para intersección principal (10,30)
     * 
     * Funciones:
     * - Sistema de turnos alternos: AZULES_PASANDO ↔ VERDES_PASANDO
     * - Previene colisiones en la intersección más transitada
     * - Implementa prioridad dinámica según condiciones de tráfico
     * - Usa monitores (locks + conditions) para sincronización
     */
    public static final CruceJusto1030 cruce1030 = new CruceJusto1030();

    // === PARCHE DE CIERRE SEGURO PARA LA INTERFAZ GRÁFICA ===
    /**
     * Instala manejadores de cierre seguro para todas las ventanas
     * 
     * Problema: Karel sometimes doesn't handle window closing properly
     * Solución: Override window close behavior to call System.exit(0)
     * 
     * Esto garantiza que la aplicación termine completamente cuando
     * el usuario cierre la ventana gráfica.
     */
    private static void instalarParcheCierreSeguro() {
        for (Window w : Window.getWindows()) {
            try {
                // Remover listeners existentes para evitar conflictos
                for (var l : w.getWindowListeners()) {
                    w.removeWindowListener(l);
                }
                
                // Instalar nuevo listener que fuerza cierre completo
                w.addWindowListener(new WindowAdapter() {
                    @Override 
                    public void windowClosing(WindowEvent e) { 
                        System.exit(0); 
                    }
                });
            } catch (Throwable ignored) { 
                // Ignorar errores - es un parche defensivo
            }
        }
    }

    /**
     * MÉTODO PRINCIPAL - Configuración e inicio del sistema
     * 
     * Secuencia de inicialización:
     * 1. Cargar mundo de Karel desde archivo
     * 2. Configurar interfaz gráfica
     * 3. Instalar parches de seguridad
     * 4. Iniciar controlador de tráfico inteligente
     * 5. Crear y distribuir robots por el mapa
     * 6. Lanzar todos los robots en hilos concurrentes
     */
    public static void main(String[] args) {
        // === CONFIGURACIÓN DEL MUNDO DE KAREL ===
        World.readWorld("Carretera.kwld");  // Cargar mapa desde archivo
        World.setVisible(true);             // Mostrar interfaz gráfica
        World.setDelay(10);                 // Velocidad de animación (10ms)

        // === INICIALIZACIÓN DE SISTEMAS ===
        instalarParcheCierreSeguro();                    // Parche para cierre limpio
        new ControlTraficoInteligente().start();         // Sistema de control global

        // === PREPARACIÓN PARA CREAR ROBOTS ===
        int id = 1;                                     // Contador de IDs únicos
        List<RobotTransporte> robots = new ArrayList<>(); // Lista de todos los robots

        // === CREACIÓN DE ROBOTS AZULES ===
        /**
         * DISTRIBUCIÓN ESTRATÉGICA DE ROBOTS AZULES:
         * 
         * Ubicados en la zona oeste del mapa (avenidas 1-8)
         * Direcciones iniciales orientadas hacia sus rutas de trabajo:
         * - Robots en última avenida de cada calle → East (hacia ruta rápida)
         * - Otros robots → North (hacia calle 1 para pickup)
         * 
         * Total: 28 robots azules distribuidos en 4 calles
         */
        
        // Calle 4 (avenidas 1-8) - 8 robots
        for (int av = 1; av <= 8; av++) {
            Direction dir = (av == 8) ? East : North; // Último robot va al este
            robots.add(new RobotTransporte(4, av, dir, 0, "azul", id++));
        }
        
        // Calle 3 (avenidas 1-7) - 7 robots  
        for (int av = 1; av <= 7; av++) {
            Direction dir = (av == 7) ? East : North; // Último robot va al este
            robots.add(new RobotTransporte(3, av, dir, 0, "azul", id++));
        }
        
        // Calle 2 (avenidas 1-7) - 7 robots
        for (int av = 1; av <= 7; av++) {
            Direction dir = (av == 7) ? East : North; // Último robot va al este
            robots.add(new RobotTransporte(2, av, dir, 0, "azul", id++));
        }
        
        // Calle 1 (avenidas 1-6) - 6 robots
        for (int av = 1; av <= 6; av++) {
            // Todos orientados al norte (ya están en calle de pickup)
            robots.add(new RobotTransporte(1, av, North, 0, "azul", id++));
        }

        // === CREACIÓN DE ROBOTS VERDES ===
        /**
         * DISTRIBUCIÓN ESTRATÉGICA DE ROBOTS VERDES:
         * 
         * Ubicados en la zona este del mapa (avenidas 23-30)
         * Todos orientados al West para dirigirse hacia:
         * 1. Su punto de pickup en (12,23)
         * 2. La ruta rápida por calle 10
         * 
         * Total: 34 robots verdes distribuidos en 5 calles
         * Concentración mayor cerca del pickup (12,23)
         */
        
        // Calle 13 (avenidas 23-30) - 8 robots
        for (int av = 23; av <= 30; av++) {
            robots.add(new RobotTransporte(13, av, West, 0, "verde", id++));
        }
        
        // Calle 12 (avenidas 28-29) - 2 robots [CERCA DEL PICKUP (12,23)]
        for (int av = 28; av <= 29; av++) {
            robots.add(new RobotTransporte(12, av, West, 0, "verde", id++));
        }
        
        // Calle 14 (avenidas 23-30) - 8 robots
        for (int av = 23; av <= 30; av++) {
            robots.add(new RobotTransporte(14, av, West, 0, "verde", id++));
        }
        
        // Calle 15 (avenidas 23-30) - 8 robots  
        for (int av = 23; av <= 30; av++) {
            robots.add(new RobotTransporte(15, av, West, 0, "verde", id++));
        }
        
        // Calle 16 (avenidas 29-30) - 2 robots
        for (int av = 29; av <= 30; av++) {
            robots.add(new RobotTransporte(16, av, West, 0, "verde", id++));
        }

        // === LANZAMIENTO DEL SISTEMA CONCURRENTE ===
        /**
         * INICIO MASIVO DE ROBOTS:
         * 
         * Cada robot se ejecuta en su propio hilo (Thread):
         * - Total: ~62 robots ejecutándose concurrentemente
         * - Cada robot alterna entre ciclos azules y verdes
         * - Sistemas de control previenen colisiones y deadlocks
         * - La simulación continúa hasta transportar 1000 pasajeros (500 + 500)
         * 
         * Los robots coordinarán automáticamente usando:
         * - Semáforos para ocupación de casillas
         * - Corredores con ventanas de tiempo
         * - Monitores para cruces críticos
         * - Sistema de prioridades dinámicas
         */
        System.out.println(" Iniciando sistema de transporte con " + robots.size() + " robots...");
        System.out.println(" Meta: " + META_AZUL + " azules + " + META_VERDE + " verdes = " + 
                          (META_AZUL + META_VERDE) + " pasajeros totales");
        
        for (RobotTransporte r : robots) {
            new Thread(r).start(); // Lanzar cada robot en su propio hilo
        }
        
        System.out.println(" Todos los robots han sido lanzados. ¡Sistema en funcionamiento!");
}
}
