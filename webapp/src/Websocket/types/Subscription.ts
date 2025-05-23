import {UnderFloorHeaterResponse} from "./UnderFloorHeater";
import {EvChargingEvent} from "./EVChargingStation";
import {EnvironmentSensorEvent} from "./EnvironmentSensors";
import {QuickStatsResponse} from "./QuickStats";
import {ESSState} from "./EnergyStorageSystem";
import {FirewallState} from "./Firewall";
import {GarageLightStatus, GarageVentilationState} from "./Garage";
import {HoermannE4Broadcast} from "./garage.domain";

export type SubscriptionType =
    "getGarageLightStatus"
    | "getGarageDoorStatus"
    | "getGarageVentilationStatus"
    | "getUnderFloorHeaterStatus"
    | "evChargingStationEvents"
    | "environmentSensorEvents"
    | "quickStatsEvents"
    | "firewall"
    | "essState";

export type Subscribe = {
    subscriptionId: string;
    type: SubscriptionType;
}

export type Unsubscribe = {
    subscriptionId: string;
}

export type Notify = {
    subscriptionId: string;
    garageStatus?: GarageLightStatus;
    underFloorHeaterStatus?: UnderFloorHeaterResponse;
    evChargingStationEvent?: EvChargingEvent;
    environmentSensorEvent?: EnvironmentSensorEvent;
    quickStatsResponse?: QuickStatsResponse;
    essState?: ESSState;
    firewallState?: FirewallState;
    hoermannE4Broadcast?: HoermannE4Broadcast;
    garageVentilationState?: GarageVentilationState;
}

