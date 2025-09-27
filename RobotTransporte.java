import kareltherobot.*;
import java.awt.Color;
import java.util.concurrent.Semaphore;

/**
 * Robot de transporte que opera en un sistema de tráfico concurrente
 * 
 * Cada robot alterna entre recoger pasajeros azules y verdes:
 * - Ciclo AZUL: (1,7) → (12,30) - Ruta rápida por calle 1
 * - Ciclo VERDE: (12,23) → (1,7) - Ruta rápida por calle 10 y avenida 30
 * 
 * Implementa sistemas de control de tráfico para evitar colisiones y deadlocks.
 */
public class RobotTransporte extends Robot implements Runnable {
    // === IDENTIFICACIÓN DEL ROBOT ===
    private final int id;                // ID único del robot
    private final String zona;           // Color inicial ("azul" o "verde")
    private int inicioCalle;             // Posición actual - calle
    private int inicioAvenida;           // Posición actual - avenida

    // === PATRÓN RAII PARA GESTIÓN SEGURA DE SEMÁFOROS ===
    /**
     * Clase que garantiza la liberación automática de semáforos
     * 
     * Previene deadlocks asegurando que los semáforos se liberen
     * incluso si ocurre una excepción durante el movimiento.
     * 
     * Uso: try (Permit p = new Permit(semaforo)) { p.acquire(); ... }
     */
    private static final class Permit implements AutoCloseable {
        private final Semaphore s;      // Semáforo a controlar
        private boolean held = false;   // Flag: ¿tenemos el permiso?
        
        Permit(Semaphore s) { this.s = s; }
        
        /** Adquiere el semáforo y marca que lo tenemos */
        void acquire() throws InterruptedException { 
            s.acquire(); 
            held = true; 
        }
        
        /** Intenta adquirir con timeout - retorna true si lo obtuvo */
        boolean tryAcquire(long ms) throws InterruptedException {
            boolean ok = s.tryAcquire(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (ok) held = true;
            return ok;
        }
        
        /** Liberación automática al salir del try-with-resources */
        @Override 
        public void close() { 
            if (held) { 
                s.release(); 
                held = false; 
            } 
        }
    }

    /**
     * Constructor del robot de transporte
     * 
     * @param street Calle inicial
     * @param avenue Avenida inicial  
     * @param dir Dirección inicial
     * @param beepers Número de beepers inicial
     * @param zona Color del robot ("azul" o "verde")
     * @param id Identificador único del robot
     */
    public RobotTransporte(int street, int avenue, Direction dir, int beepers, String zona, int id) {
        // Crear robot con color según su zona
        super(street, avenue, dir, beepers, zona.equals("azul") ? Color.BLUE : Color.GREEN);
        
        // Inicializar propiedades
        this.id = id; 
        this.zona = zona; 
        this.inicioCalle = street; 
        this.inicioAvenida = avenue;
        
        // Configurar para ejecutar en hilo separado
        World.setupThread(this);
        System.out.println("Robot " + id + " (" + zona + ") en (" + street + "," + avenue + ")");
        
        // Intentar ocupar posición inicial
        try {
            if (!CarreteraPareYSiga.ocupacion[street][avenue].tryAcquire()) {
                System.out.println("Inicio ocupado r" + id);
            }
        } catch (Exception e) { 
            Thread.currentThread().interrupt(); 
        }
    }

    /**
     * Bucle principal del robot - alterna entre ciclos azules y verdes
     * 
     * CICLO AZUL: Pickup azul (1,7) → Entrega (12,30)
     * CICLO VERDE: Pickup verde (12,23) → Entrega (1,7) 
     * 
     * Continúa hasta que no queden más pasajeros pendientes
     */
    @Override 
    public void run() {
        // Delay inicial para evitar colisiones en el inicio
        try { 
            Thread.sleep(id * 200); 
        } catch (InterruptedException e) { 
            Thread.currentThread().interrupt(); 
        }

        // Empezar con el ciclo de su color inicial
        boolean esCicloAzul = zona.equals("azul");

        // Continuar mientras haya pasajeros pendientes de cualquier color
        while (CarreteraPareYSiga.faltanAzul.get() > 0 || CarreteraPareYSiga.faltanVerde.get() > 0) {
            
            if (esCicloAzul) {
                // === CICLO AZUL: Recoger azules y llevarlos a su destino ===
                if (CarreteraPareYSiga.faltanAzul.get() <= 0) { 
                    esCicloAzul = false; 
                    continue; 
                }
                
                irAPickupAzul();                    // Ir a punto de recogida (1,7)
                if (cargarPasajeros() > 0) {        // Cargar hasta 4 pasajeros
                    CarreteraPareYSiga.faltanAzul.decrementAndGet();
                    caminoRapidoAzulConPareYSiga(); // Ruta rápida a (12,30)
                    descargarPasajeros();           // Entregar pasajeros
                } else {
                    // No hay pasajeros, esperar un poco
                    try { 
                        Thread.sleep(1000); 
                    } catch (InterruptedException e) { 
                        Thread.currentThread().interrupt(); 
                    }
                    continue;
                }
                
            } else {
                // === CICLO VERDE: Recoger verdes y llevarlos a su destino ===
                if (CarreteraPareYSiga.faltanVerde.get() <= 0) { 
                    esCicloAzul = true; 
                    continue; 
                }
                
                irAPickupVerde();                     // Ir a punto de recogida (12,23)
                if (cargarPasajeros() > 0) {          // Cargar hasta 4 pasajeros
                    CarreteraPareYSiga.faltanVerde.decrementAndGet();
                    caminoRapidoVerdeConPareYSiga();  // Ruta rápida a (1,7)
                    descargarPasajeros();             // Entregar pasajeros
                    irDeZonaVerdeAZonaAzul();         // Regresar a zona azul
                } else {
                    // No hay pasajeros, esperar un poco
                    try { 
                        Thread.sleep(1000); 
                    } catch (InterruptedException e) { 
                        Thread.currentThread().interrupt(); 
                    }
                    continue;
                }
            }
            
            // Alternar al siguiente ciclo
            esCicloAzul = !esCicloAzul;
        }
        
        // Apagar robot cuando no hay más trabajo
        turnOff();
    }

    // === VERIFICACIÓN DE POSICIONES DE PICKUP ===
    /** Verifica si está en el punto de recogida de pasajeros azules */
    private boolean enPickupAzul()  { return inicioCalle == 1  && inicioAvenida == 7; }
    
    /** Verifica si está en el punto de recogida de pasajeros verdes */
    private boolean enPickupVerde() { return inicioCalle == 12 && inicioAvenida == 23; }

    /**
     * Carga pasajeros en el punto de pickup actual
     * 
     * - Solo funciona en puntos de pickup válidos (1,7) o (12,23)
     * - Máximo 4 pasajeros por viaje
     * - Simula tiempo de carga con delays
     * 
     * @return Número de pasajeros cargados
     */
    private int cargarPasajeros() {
        // Solo cargar si estamos en un punto de pickup válido
        if (!(enPickupAzul() || enPickupVerde())) return 0;
        
        int contador = 0;
        // Cargar hasta 4 beepers (pasajeros)
        while (nextToABeeper() && contador < 4) {
            pickBeeper();
            contador++;
            // Simular tiempo de carga
            try { 
                Thread.sleep(120); 
            } catch (InterruptedException e) { 
                Thread.currentThread().interrupt(); 
            }
        }
        
        System.out.println("r" + id + " cargó " + contador);
        return contador;
    }

    /**
     * Descarga todos los pasajeros en el destino actual
     * 
     * - Entrega todos los beepers (pasajeros) que lleva
     * - Simula tiempo de descarga con delays
     * - Intenta avanzar una casilla después de descargar
     */
    private void descargarPasajeros() {
        // Entregar todos los pasajeros
        while (anyBeepersInBeeperBag()) {
            putBeeper();
            // Simular tiempo de descarga
            try { 
                Thread.sleep(120); 
            } catch (InterruptedException e) { 
                Thread.currentThread().interrupt(); 
            }
        }
        
        // Intentar avanzar una casilla después de descargar
        int cD = inicioCalle, aD = inicioAvenida;
        if (facingNorth()) cD++; 
        else if (facingSouth()) cD--; 
        else if (facingEast()) aD++; 
        else if (facingWest()) aD--;
        
        // Verificar que el destino está dentro de los límites del mapa
        boolean dentro = (cD >= 1 && cD <= CarreteraPareYSiga.MAX_CALLES && 
                         aD >= 1 && aD <= CarreteraPareYSiga.MAX_AVENIDAS);
        
        if (dentro && frontIsClear()) { 
            avanzar(); 
        } else { 
            // Si no puede avanzar, liberar la casilla actual
            try { 
                CarreteraPareYSiga.ocupacion[inicioCalle][inicioAvenida].release(); 
            } catch (Exception ignore) {} 
        }
    }

    // === MÉTODOS DE VERIFICACIÓN DE TRÁFICO ===
    
    /**
     * Verifica si la zona crítica (1,26-29) está libre
     * 
     * Esta zona es crítica porque:
     * - Es parte de la ruta rápida de robots azules
     * - Incluye el cruce con avenida 29 donde bajan los robots verdes
     * 
     * @return true si todas las casillas (1,26) a (1,29) están libres
     */
    private boolean zona129Libre() {
        return  CarreteraPareYSiga.ocupacion[1][26].availablePermits() == 1 &&
                CarreteraPareYSiga.ocupacion[1][27].availablePermits() == 1 &&
                CarreteraPareYSiga.ocupacion[1][28].availablePermits() == 1 &&
                CarreteraPareYSiga.ocupacion[1][29].availablePermits() == 1;
    }

    /**
     * Verifica si el tramo (1,16) a (1,21) está libre
     * Zona de entrada al corredor C1 - debe estar despejada para usar ruta rápida
     */
    private boolean tramo1_16a1_21_libre() {
        for (int av = 16; av <= 21; av++) {
            if (CarreteraPareYSiga.ocupacion[1][av].availablePermits() == 0) return false;
        }
        return true;
    }
    
    /**
     * Espera en (1,15) hasta que la zona (1,16-21) esté libre
     * Previene que robots entren al corredor si hay congestión
     */
    private void esperarEn_1_15_hastaZona_1_16a1_21_libre() {
        try { 
            while (!tramo1_16a1_21_libre()) {
                Thread.sleep(120); 
            }
        } catch (InterruptedException e) { 
            Thread.currentThread().interrupt(); 
        }
    }
    
    /**
     * Verifica si hay robots en el tramo (1,15) a (1,21)
     * Usado por robots verdes para evitar interferir con azules
     */
    private boolean hayCarrosEn_1_15_a_1_21() {
        for (int av = 15; av <= 21; av++) {
            if (CarreteraPareYSiga.ocupacion[1][av].availablePermits() == 0) return true;
        }
        return false;
    }
    
    /**
     * Espera en (2,21) si la calle 1 (15-21) está ocupada
     * Los robots verdes esperan a que pasen los azules por su ruta rápida
     */
    private void esperarEn_2_21_siCalle1_15a21_ocupada() {
        try { 
            while (hayCarrosEn_1_15_a_1_21()) {
                Thread.sleep(120); 
            }
        } catch (InterruptedException e) { 
            Thread.currentThread().interrupt(); 
        }
    }

    /**
     * Verifica si el tramo de avenida 30 (calles 5-10) está ocupado
     * Zona compartida entre robots azules subiendo y verdes bajando
     */
    private boolean tramoAv30_ocupado_5a10() {
        for (int c = 5; c <= 10; c++) {
            if (CarreteraPareYSiga.ocupacion[c][30].availablePermits() == 0) return true;
        }
        return false;
    }
    
    /**
     * Espera en (4,30) hasta que la zona (5,30)-(10,30) esté libre
     * Los robots azules esperan antes de subir por avenida 30
     */
    private void esperarDesde_4_30() {
        try { 
            while (tramoAv30_ocupado_5a10()) { 
                Thread.sleep(80); 
            } 
        } catch (InterruptedException e) { 
            Thread.currentThread().interrupt(); 
        }
    }

    /**
     * Verifica si las casillas (1,12-15) están todas ocupadas
     * Si están ocupadas, los robots azules deben usar la ruta larga
     * Esta zona es el cuello de botella antes del corredor C1
     */
    private boolean casillas_1_12a15_todasOcupadas() {
        for (int av = 12; av <= 15; av++) {
            if (CarreteraPareYSiga.ocupacion[1][av].availablePermits() == 1) return false;
        }
        return true;
    }

    private void irAPickupAzul() {
        if (inicioCalle == 1 && inicioAvenida == 7) return;
        if (inicioCalle == 1) {
            while (!facingEast()) turnLeft();
            while (inicioAvenida < 7) avanzar();
        } else if (inicioCalle == 2) {
            while (!facingWest()) turnLeft();
            while (inicioAvenida > 1) avanzar();
            while (!facingSouth()) turnLeft();
            avanzar();
            while (!facingEast()) turnLeft();
            while (inicioAvenida < 7) avanzar();
        } else if (inicioCalle == 3) {
            while (!facingEast()) turnLeft();
            while (inicioAvenida < 7) avanzar();
            while (!facingSouth()) turnLeft();
            avanzar();
            while (!facingWest()) turnLeft();
            while (inicioAvenida > 1) avanzar();
            while (!facingSouth()) turnLeft();
            avanzar();
            while (!facingEast()) turnLeft();
            while (inicioAvenida < 7) avanzar();
        } else if (inicioCalle == 4) {
            while (!facingWest()) turnLeft();
            while (inicioAvenida > 1) avanzar();
            while (!facingSouth()) turnLeft();
            avanzar();
            while (!facingEast()) turnLeft();
            while (inicioAvenida < 7) avanzar();
            while (!facingSouth()) turnLeft();
            avanzar();
            while (!facingWest()) turnLeft();
            while (inicioAvenida > 1) avanzar();
            while (!facingSouth()) turnLeft();
            avanzar();
            while (!facingEast()) turnLeft();
            while (inicioAvenida < 7) avanzar();
        }
    }

    private void irAPickupVerde() {
        if (inicioCalle == 12 && inicioAvenida == 23) return;
        if (inicioAvenida == 30 && inicioCalle >= 12) {
            while (!facingNorth()) turnLeft();
            while (inicioCalle < 16) avanzar();
        }
        if (inicioCalle == 16) {
            while (!facingWest()) turnLeft();
            while (inicioAvenida > 29) avanzar();
            while (!facingSouth()) turnLeft();
            avanzar();
        }
        if (inicioCalle == 15) {
            while (!facingWest()) turnLeft();
            while (inicioAvenida > 23) avanzar();
            while (!facingSouth()) turnLeft();
            avanzar();
        }
        if (inicioCalle == 14) {
            while (!facingEast()) turnLeft();
            while (inicioAvenida < 29) avanzar();
            while (!facingSouth()) turnLeft();
            avanzar();
        }
        if (inicioCalle == 13 && inicioAvenida == 29) {
            while (!facingSouth()) turnLeft();
            avanzar();
        }
        if (inicioCalle == 12 && (inicioAvenida == 28 || inicioAvenida == 29)) {
            while (!facingWest()) turnLeft();
            while (inicioAvenida > 28) avanzar();
            while (!facingNorth()) turnLeft();
            avanzar();
        }
        if (inicioCalle == 13) {
            while (!facingWest()) turnLeft();
            while (inicioAvenida > 23) avanzar();
            while (!facingSouth()) turnLeft();
            avanzar();
        }
        if (inicioCalle != 12 || inicioAvenida != 23) {
            if (inicioAvenida > 23) {
                while (!facingWest()) turnLeft();
                while (inicioAvenida > 23) avanzar();
            } else if (inicioAvenida < 23) {
                while (!facingEast()) turnLeft();
                while (inicioAvenida < 23) avanzar();
            }
            if (inicioCalle > 12) {
                while (!facingSouth()) turnLeft();
                while (inicioCalle > 12) avanzar();
            } else if (inicioCalle < 12) {
                while (!facingNorth()) turnLeft();
                while (inicioCalle < 12) avanzar();
            }
        }
    }

    /**
     * Regresa de la zona verde (1,7) a la zona azul
     * Movimiento simple: subir 2 casillas al norte
     */
    private void irDeZonaVerdeAZonaAzul() { 
        while (!facingNorth()) turnLeft(); 
        avanzar(); 
        avanzar(); 
    }

    /**
     * RUTA RÁPIDA AZUL: (1,7) → (12,30)
     * 
     * Recorrido: 
     * 1. Calle 1 hacia el este (corredor C1) hasta avenida 29
     * 2. Subir por avenida 30 hasta calle 12
     * 
     * Sistemas de control:
     * - Corredor C1: Ventanas de tiempo para evitar congestión
     * - Control AV29: Prioridad para robots verdes en zona crítica
     * - Cruce 1030: Sistema de turnos para intersección principal
     */
    private void caminoRapidoAzulConPareYSiga() {
        // Orientarse hacia el este para la ruta rápida
        while (!facingEast()) turnLeft();
        
        // Avanzar desde (1,7) hasta (1,11)
        for (int i = 0; i < 4; i++) avanzar();
        
        // Verificar si la entrada al corredor está congestionada
        if (casillas_1_12a15_todasOcupadas()) { 
            usarRutaLargaAzul(); 
            return; 
        }
        avanzar(); // Llegar a (1,12)

        boolean marcado129 = false; // Flag para control de congestión AV29

        try {
            // === ENTRADA AL CORREDOR C1 ===
            CarreteraPareYSiga.corredorC1.enter(true);
            
            // Intentar obtener ventana de tiempo en el corredor
            if (!CarreteraPareYSiga.corredorC1.tryEnterVentana()) { 
                CarreteraPareYSiga.corredorC1.exit(true); 
                usarRutaLargaAzul(); 
                return; 
            }

            // === RECORRIDO POR CALLE 1 (avenidas 12-29) ===
            for (int av = 12; av < 30; av++) {
                // Punto de control en (1,15) - Verificar que zona ahead esté libre
                if (av == 14) { 
                    avanzar(); 
                    esperarEn_1_15_hastaZona_1_16a1_21_libre(); 
                    continue; 
                }
                
                // Salir de la ventana del corredor en (1,16)
                if (av == 15) CarreteraPareYSiga.corredorC1.leaveVentana();

                // Pausa periódica cada 4 casillas para simular tráfico realista
                if ((av - 12) % 4 == 0 && av > 12) {
                    try { 
                        Thread.sleep(120); 
                    } catch (InterruptedException e) { 
                        Thread.currentThread().interrupt(); 
                    }
                }

                // === ZONA CRÍTICA: Aproximación a AV29 ===
                if (av == 24) {
                    avanzar(); // Mover a (1,25)
                    
                    // Registrarse como robot azul aproximándose a (1,29)
                    if (!marcado129) { 
                        CarreteraPareYSiga.azulesAproximando129.incrementAndGet(); 
                        marcado129 = true; 
                    }

                    // Solicitar permiso al control de congestión AV29
                    try {
                        CarreteraPareYSiga.controlAv29.solicitarPasoAzul();
                    } catch (InterruptedException e) { 
                        Thread.currentThread().interrupt(); 
                    }

                    // Esperar hasta que la zona crítica y cruce estén libres
                    try {
                        while (!(zona129Libre() && CarreteraPareYSiga.cruce_1_26.availablePermits() == 1)) {
                            tryAcquireSilencioso(avisoAzulEn_1_25());
                            Thread.sleep(80);
                        }
                    } catch (InterruptedException e) { 
                        Thread.currentThread().interrupt(); 
                    }
                    continue;
                }
                // Cruce intermedio (1,26) - Control con semáforo
                if (av == 25) {
                    try (Permit p = new Permit(CarreteraPareYSiga.cruce_1_26)) { 
                        p.acquire(); 
                        avanzar(); 
                    } catch (InterruptedException e) { 
                        Thread.currentThread().interrupt(); 
                    }
                    continue;
                }

                // === CRUCE CRÍTICO (1,29) - Intersección con AV29 ===
                if (av == 28) {
                    // Usar RAII para garantizar liberación del cruce crítico
                    try (Permit p = new Permit(CarreteraPareYSiga.cruce_1_29)) {
                        try { 
                            p.acquire(); 
                        } catch (InterruptedException ie) { 
                            Thread.currentThread().interrupt(); 
                            return; 
                        }
                        avanzar(); // Pasar a (1,29)
                    }
                    
                    // Desregistrarse del sistema de control AV29
                    if (marcado129) { 
                        CarreteraPareYSiga.azulesAproximando129.decrementAndGet(); 
                        marcado129 = false; 
                    }
                } else {
                    // Movimiento normal en otras avenidas
                    avanzar();
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Limpieza garantizada: desregistrarse del control AV29 y salir del corredor
            if (marcado129) { 
                CarreteraPareYSiga.azulesAproximando129.decrementAndGet(); 
                marcado129 = false; 
            }
            CarreteraPareYSiga.corredorC1.exit(true);
        }

        try {
            // === CRUCE PRINCIPAL (10,30) - Sistema de turnos ===
            CarreteraPareYSiga.cruce1030.entrarCruce(CruceJusto1030.Turno.AZULES_PASANDO);
            
            // Orientarse hacia el norte para subir por avenida 30
            while (!facingNorth()) turnLeft();

            // === SUBIDA POR AVENIDA 30 hasta destino (12,30) ===
            while (inicioCalle < 12) {
                // Zona de conflicto (5,30) - Coordinación con robots verdes
                if (inicioCalle == 4 && inicioAvenida == 30) {
                    esperarDesde_4_30(); // Esperar que zona 5-10 esté libre
                    
                    // Control especial para cruce (5,30) compartido con verdes
                    try (Permit p530 = new Permit(CarreteraPareYSiga.cruce_5_30)) {
                        // Esperar si hay verdes bajando por AV30
                        while (CarreteraPareYSiga.verdesBajandoAv30.get() > 0) {
                            try {
                                // Obtener permiso especial para azules en (5,30)
                                if (CarreteraPareYSiga.permisoAzulEn_5_30.tryAcquire(200, 
                                    java.util.concurrent.TimeUnit.MILLISECONDS)) break;
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                CarreteraPareYSiga.cruce1030.salirCruce();
                                return;
                            }
                        }
                        
                        // Adquirir cruce y avanzar
                        try { 
                            p530.acquire(); 
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            CarreteraPareYSiga.cruce1030.salirCruce();
                            return;
                        }
                        avanzar();
                    }
                } else {
                    // Movimiento normal en otras calles de AV30
                    avanzar();
                }
            }
            
            // Salir del sistema de turnos del cruce principal
            CarreteraPareYSiga.cruce1030.salirCruce();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CarreteraPareYSiga.cruce1030.salirCruce();
        }
    }

    private void caminoRapidoVerdeConPareYSiga() {
        while (!facingWest()) turnLeft();

        try {
            while (!facingSouth()) turnLeft();
            avanzar();

            if (casillas_10_24a29_todasOcupadas()) {
                while (!facingWest()) turnLeft();
                usarRutaLargaVerde();
                return;
            }
            avanzar();

            CarreteraPareYSiga.corredorC10.enter(true);
            if (!CarreteraPareYSiga.corredorC10.tryEnterVentana()) { CarreteraPareYSiga.corredorC10.exit(true); usarRutaLargaVerde(); return; }

            while (!facingEast()) turnLeft();
            for (int av = 23; av < 29; av++) {
                if ((av - 23) % 2 == 0 && av > 23) {
                    try { Thread.sleep(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }

                if (av == 27) {
                    boolean aplicaPrioridadCompleta =
                            (CarreteraPareYSiga.ocupacion[4][30].availablePermits() == 0 &&
                             CarreteraPareYSiga.ocupacion[3][30].availablePermits() == 0 &&
                             CarreteraPareYSiga.ocupacion[2][30].availablePermits() == 0 &&
                             CarreteraPareYSiga.ocupacion[5][29].availablePermits() == 1 &&
                             CarreteraPareYSiga.ocupacion[4][29].availablePermits() == 1);

                    boolean aplicaPrioridadLimitada =
                            (CarreteraPareYSiga.ocupacion[4][30].availablePermits() == 0 &&
                             CarreteraPareYSiga.ocupacion[3][30].availablePermits() == 0 &&
                             CarreteraPareYSiga.ocupacion[2][30].availablePermits() == 0 &&
                             CarreteraPareYSiga.ocupacion[5][29].availablePermits() == 1 &&
                             CarreteraPareYSiga.ocupacion[4][29].availablePermits() == 0);

                    if (aplicaPrioridadCompleta) {
                        System.out.println("Verde " + id + " en (10,28) prioridad completa");
                    } else if (aplicaPrioridadLimitada) {
                        System.out.println("Verde " + id + " en (10,28) prioridad limitada; espera");
                        while (aplicaPrioridadLimitada && CarreteraPareYSiga.ocupacion[10][29].availablePermits() == 0) {
                            try {
                                Thread.sleep(100);
                                aplicaPrioridadLimitada =
                                        (CarreteraPareYSiga.ocupacion[4][30].availablePermits() == 0 &&
                                         CarreteraPareYSiga.ocupacion[3][30].availablePermits() == 0 &&
                                         CarreteraPareYSiga.ocupacion[2][30].availablePermits() == 0 &&
                                         CarreteraPareYSiga.ocupacion[5][29].availablePermits() == 1 &&
                                         CarreteraPareYSiga.ocupacion[4][29].availablePermits() == 0);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
                avanzar();
            }

            CarreteraPareYSiga.cruce1030.entrarCruce(CruceJusto1030.Turno.VERDES_PASANDO);

            boolean aplicaPrioridadCompleta =
                    (CarreteraPareYSiga.ocupacion[10][29].availablePermits() == 0 &&
                     CarreteraPareYSiga.ocupacion[4][30].availablePermits() == 0 &&
                     CarreteraPareYSiga.ocupacion[3][30].availablePermits() == 0 &&
                     CarreteraPareYSiga.ocupacion[2][30].availablePermits() == 0 &&
                     CarreteraPareYSiga.ocupacion[5][29].availablePermits() == 1 &&
                     CarreteraPareYSiga.ocupacion[4][29].availablePermits() == 1);

            boolean aplicaPrioridadLimitada =
                    (CarreteraPareYSiga.ocupacion[10][29].availablePermits() == 0 &&
                     CarreteraPareYSiga.ocupacion[4][30].availablePermits() == 0 &&
                     CarreteraPareYSiga.ocupacion[3][30].availablePermits() == 0 &&
                     CarreteraPareYSiga.ocupacion[2][30].availablePermits() == 0 &&
                     CarreteraPareYSiga.ocupacion[5][29].availablePermits() == 1 &&
                     CarreteraPareYSiga.ocupacion[4][29].availablePermits() == 0);

            if (!aplicaPrioridadCompleta && !aplicaPrioridadLimitada) {
                boolean zonaSegura = false;
                while (!zonaSegura) {
                    zonaSegura = true;
                    for (int calle = 4; calle <= 10; calle++) {
                        if (CarreteraPareYSiga.ocupacion[calle][30].availablePermits() == 0) { zonaSegura = false; break; }
                    }
                    if (CarreteraPareYSiga.cruce_5_30.availablePermits() == 0) zonaSegura = false;
                    if (CarreteraPareYSiga.ocupacion[4][29].availablePermits() == 0 &&
                        CarreteraPareYSiga.ocupacion[5][29].availablePermits() == 0) zonaSegura = false;
                    if (!zonaSegura) {
                        try { Thread.sleep(100); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); CarreteraPareYSiga.cruce1030.salirCruce(); return; }
                    }
                }
            } else if (aplicaPrioridadLimitada) {
                System.out.println("Verde " + id + " en (10,29) prioridad limitada");
            } else if (aplicaPrioridadCompleta) {
                System.out.println("Verde " + id + " en (10,29) prioridad completa");
            }

            avanzar();
            CarreteraPareYSiga.corredorC10.leaveVentana();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            CarreteraPareYSiga.corredorC10.exit(true);
        }

        try {
            continuarRutaRapidaVerde();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void continuarRutaRapidaVerde() throws InterruptedException {
        try {
            while (!facingSouth()) turnLeft();
            CarreteraPareYSiga.verdesBajandoAv30.incrementAndGet();

            for (int i = 0; i < 5; i++) {
                if (inicioCalle == 6 && inicioAvenida == 30) {
                    try (Permit p = new Permit(CarreteraPareYSiga.cruce_5_30)) { p.acquire(); avanzar(); }
                    CarreteraPareYSiga.permisoAzulEn_5_30.release();
                } else {
                    avanzar();
                }
            }
            CarreteraPareYSiga.verdesBajandoAv30.decrementAndGet();

            while (!facingWest()) turnLeft();
            avanzar();
            CarreteraPareYSiga.cruce1030.salirCruce();

            while (!facingSouth()) turnLeft();
            for (int i = 0; i < 4; i++) {
                if (i == 3) {
                    boolean esPrioritarioAv29 = false;
                    if ((inicioCalle == 3 || inicioCalle == 2) && inicioAvenida == 29) {
                        if (CarreteraPareYSiga.controlAv29.debeAplicarsePrioridadAv29()) {
                            CarreteraPareYSiga.controlAv29.iniciarPrioridadVerde();
                            if (CarreteraPareYSiga.controlAv29.tomarCupoVerde()) {
                                esPrioritarioAv29 = true;
                                CarreteraPareYSiga.controlAv29.registrarVerdeEnTransito();
                                System.out.println("Verde " + id + " obtuvo cupo ventana AV29");
                            }
                        }
                    }

                    if (!esPrioritarioAv29) {
                        while (CarreteraPareYSiga.azulesAproximando129.get() > 0) {
                            avisoAzulEn_1_25().release();
                            try { Thread.sleep(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        }
                    }

                    try (Permit p = new Permit(CarreteraPareYSiga.cruce_1_29)) { p.acquire(); avanzar(); }
                    if (esPrioritarioAv29) CarreteraPareYSiga.controlAv29.registrarVerdeSalio();
                } else {
                    avanzar();
                }
            }

            while (!facingWest()) turnLeft();
            for (int i = 0; i < 3; i++) {
                if (i == 2) { try (Permit p = new Permit(CarreteraPareYSiga.cruce_1_26)) { p.acquire(); avanzar(); } }
                else avanzar();
            }

            while (!facingNorth()) turnLeft(); avanzar();
            while (!facingWest()) turnLeft(); for (int i = 0; i < 5; i++) avanzar();
            esperarEn_2_21_siCalle1_15a21_ocupada();

            while (!facingSouth()) turnLeft();
            try (Permit p = new Permit(CarreteraPareYSiga.cruce_1_21)) { p.acquire(); avanzar(); }

            while (!facingWest()) turnLeft();
            for (int i = 0; i < 5; i++) {
                if (i == 4) { try (Permit p = new Permit(CarreteraPareYSiga.cruce_1_16)) { p.acquire(); avanzar(); } }
                else avanzar();
            }

            while (!facingNorth()) turnLeft(); avanzar();
            while (!facingWest()) turnLeft(); for (int i = 0; i < 7; i++) avanzar();
        } finally {
            if (CarreteraPareYSiga.ocupacion[inicioCalle][inicioAvenida].availablePermits() == 0) {
                if (inicioCalle > 5 || inicioAvenida > 29) {
                    CarreteraPareYSiga.cruce1030.salirCruce();
                }
            }
        }
    }

    private void usarRutaLargaAzul() {
        if (inicioCalle != 1) {
            while (!facingSouth()) turnLeft();
            while (inicioCalle > 1) avanzar();
        }
        if (inicioAvenida > 11) { while (!facingWest()) turnLeft(); while (inicioAvenida > 11) avanzar(); }
        else if (inicioAvenida < 11) { while (!facingEast()) turnLeft(); while (inicioAvenida < 11) avanzar(); }

        while (!facingNorth()) turnLeft(); for (int i = 0; i < 10; i++) avanzar();
        while (!facingWest())  turnLeft(); for (int i = 0; i < 3; i++)  avanzar();
        while (!facingNorth()) turnLeft(); for (int i = 0; i < 3; i++)  avanzar();
        while (!facingEast())  turnLeft(); for (int i = 0; i < 8; i++)  avanzar();
        while (!facingSouth()) turnLeft(); for (int i = 0; i < 4; i++)  avanzar();
        while (!facingWest())  turnLeft(); for (int i = 0; i < 3; i++)  avanzar();
        while (!facingSouth()) turnLeft(); for (int i = 0; i < 5; i++)  avanzar();
        while (!facingEast())  turnLeft(); for (int i = 0; i < 7; i++)  avanzar();
        while (!facingNorth()) turnLeft(); for (int i = 0; i < 5; i++)  avanzar();

        while (!facingEast())  turnLeft();
        while (inicioAvenida < 29) { avanzar(); }

        try {
            CarreteraPareYSiga.cruce1030.entrarCruce(CruceJusto1030.Turno.AZULES_PASANDO);
            avanzar();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            CarreteraPareYSiga.cruce1030.salirCruce();
        }

        while (!facingNorth()) turnLeft();
        while (inicioCalle < 12) { avanzar(); }
    }

    private void usarRutaLargaVerde() {
        while (!facingWest()) turnLeft(); for (int i = 0; i < 3; i++) avanzar();
        while (!facingNorth()) turnLeft(); for (int i = 0; i < 8; i++) avanzar();
        while (!facingWest()) turnLeft(); for (int i = 0; i < 2; i++) avanzar();
        while (!facingSouth()) turnLeft(); for (int i = 0; i < 4; i++) avanzar();
        while (!facingWest()) turnLeft(); for (int i = 0; i < 17; i++) avanzar();
        while (!facingSouth()) turnLeft(); for (int i = 0; i < 5; i++) avanzar();
        while (!facingEast()) turnLeft(); for (int i = 0; i < 9; i++) avanzar();
        while (!facingSouth()) turnLeft(); for (int i = 0; i < 8; i++) avanzar();
        while (!facingWest()) turnLeft(); avanzar();
    }

    private boolean casillas_10_24a29_todasOcupadas() {
        for (int av = 24; av <= 29; av++) {
            if (CarreteraPareYSiga.ocupacion[10][av].availablePermits() == 1) return false;
        }
        return true;
    }

    /**
     * MOVIMIENTO SEGURO CON CONTROL DE CONCURRENCIA
     * 
     * Implementa el patrón de "reservar destino antes de mover":
     * 1. Calcula la casilla destino según la dirección actual
     * 2. Reserva el semáforo de la casilla destino (RAII)
     * 3. Realiza el movimiento físico
     * 4. Libera la casilla de origen
     * 5. Actualiza coordenadas internas
     * 
     * Garantías:
     * - No hay colisiones (dos robots en una casilla)
     * - No hay deadlocks (liberación automática con RAII)
     * - Manejo de excepciones e interrupciones
     */
    private synchronized void avanzar() {
        // Coordenadas actuales (origen)
        int cA = this.inicioCalle, aA = this.inicioAvenida;
        
        // Calcular coordenadas destino según dirección actual
        int cD = cA, aD = aA;
        if (facingNorth()) cD++; 
        else if (facingSouth()) cD--; 
        else if (facingEast()) aD++; 
        else if (facingWest()) aD--;

        // Verificar límites del mapa - si sale, apagar robot
        if (cD < 1 || cD > CarreteraPareYSiga.MAX_CALLES || 
            aD < 1 || aD > CarreteraPareYSiga.MAX_AVENIDAS) {
            try { 
                CarreteraPareYSiga.ocupacion[cA][aA].release(); 
            } catch (Exception ignore) {}
            turnOff();
            return;
        }

        // Usar RAII para manejo seguro del semáforo destino
        Permit dest = new Permit(CarreteraPareYSiga.ocupacion[cD][aD]);
        try {
            // Intentar reservar destino (con reintentos para evitar bloqueo)
            while (!dest.tryAcquire(50)) { 
                Thread.yield(); // Ceder CPU si destino está ocupado
            }
            
            // Verificar que el camino está físicamente libre
            if (frontIsClear()) {
                move();                     // Movimiento físico
                this.inicioCalle = cD;      // Actualizar coordenadas internas
                this.inicioAvenida = aD;
                
                // Liberar casilla de origen
                try { 
                    CarreteraPareYSiga.ocupacion[cA][aA].release(); 
                } catch (Exception ignore) {}
            } else {
                // Si hay obstáculo físico, liberar destino reservado
                dest.close();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            try { dest.close(); } catch (Exception ignore) {}
        } catch (Throwable t) {
            // En caso de error inesperado, limpiar ambas casillas
            try { dest.close(); } catch (Exception ignore) {}
            try { CarreteraPareYSiga.ocupacion[cA][aA].release(); } catch (Exception ignore) {}
            throw t;
        }
    }

    // === MÉTODOS AUXILIARES PARA CONTROL DE TRÁFICO ===
    
    /** Semáforo dummy para evitar bloqueos cuando no hay avisos reales */
    private static final Semaphore permisoDummy = new Semaphore(0, true);
    
    /** Retorna semáforo para avisos de robots azules en (1,25) */
    private Semaphore avisoAzulEn_1_25() { 
        return permisoDummy; 
    }
    
    /** Intenta adquirir semáforo sin bloquear - ignora excepciones */
    private void tryAcquireSilencioso(Semaphore s) { 
        try { 
            s.tryAcquire(); 
        } catch (Exception ignored) {}
}
}
