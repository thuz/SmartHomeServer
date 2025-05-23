import {Subscribe, Unsubscribe} from "./Subscription";
import {GarageLightRequest, GarageLightResponse, GarageVentilationState} from "./Garage";
import {UnderFloorHeaterRequest, UnderFloorHeaterResponse} from "./UnderFloorHeater";
import {EvChargingStationRequest, EvChargingStationResponse} from "./EVChargingStation";
import {EnvironmentSensorRequest, EnvironmentSensorResponse} from "./EnvironmentSensors";
import {VideoBrowserRequest, VideoBrowserResponse} from "./Video";
import {QuickStatsResponse} from "./QuickStats";
import {
    EnergyConsumptionData,
    EnergyConsumptionQuery,
    EnergyPricingSettingsRead,
    EnergyPricingSettingsWrite
} from "./EnergyPricingSettings";
import {ESSState, ESSWrite} from "./EnergyStorageSystem";
import {UserSettings, WriteUserSettings} from "./UserSettings";
import {WriteBms} from "./Bms";
import {StairsHeatingRequest, StairsHeatingResponse} from "./stairsHeating";
import {HoermannE4Command} from "./garage.domain";

export type RequestType =
    "subscribe"
    | "unsubscribe"
    | "garageLightRequest"
    | "underFloorHeaterRequest"
    | "evChargingStationRequest"
    | "environmentSensorRequest"
    | "videoBrowser"
    | "quickStats"
    | "energyConsumptionQuery"
    | "readEnergyPricingSettings"
    | "writeEnergyPricingSettings"
    | "essRead"
    | "essWrite"
    | "userSettings"
    | "readAllUserSettings"
    | "writeUserSettings"
    | "writeBms"
    | "stairsHeatingRequest"
    | "dnsBlockingSet"
    | "dnsBlockingUpdateStandardLists"
    | "blockedMacsSet"
    | "sendHoermannE4Command"
    | "garageVentilationRequest"
    ;

export type RpcRequest = {
    type: RequestType;
    subscribe?: Subscribe;
    unsubscribe?: Unsubscribe;

    garageLightRequest?: GarageLightRequest;
    underFloorHeaterRequest?: UnderFloorHeaterRequest;
    evChargingStationRequest?: EvChargingStationRequest;
    environmentSensorRequest?: EnvironmentSensorRequest;
    videoBrowserRequest?: VideoBrowserRequest;
    essWrite?: ESSWrite;
    energyPricingSettingsWrite?: EnergyPricingSettingsWrite;
    energyConsumptionQuery?: EnergyConsumptionQuery;
    writeUserSettings?: WriteUserSettings,
    writeBms?: WriteBms,
    stairsHeatingRequest?: StairsHeatingRequest;
    dnsBlockingLists?: string[];
    blockedMacs?: string[];
    hoermannE4Command?: HoermannE4Command;
    garageVentilationCommandMilliVolts?: number;
}

export type RpcResponse = {
    subscriptionCreated?: boolean;
    subscriptionRemoved?: boolean;

    garageLightResponse?: GarageLightResponse;
    underFloorHeaterResponse?: UnderFloorHeaterResponse;
    evChargingStationResponse?: EvChargingStationResponse;
    environmentSensorResponse?: EnvironmentSensorResponse;
    videoBrowserResponse?: VideoBrowserResponse;
    quickStatsResponse?: QuickStatsResponse;
    essState?: ESSState;
    energyPricingSettingsRead?: EnergyPricingSettingsRead;
    userSettings?: UserSettings;
    energyConsumptionData?: EnergyConsumptionData;
    allUserSettings?: { [userId: string]: UserSettings },
    stairsHeatingResponse?: StairsHeatingResponse;
    hoermannE4CommandResult?: boolean;
    garageVentilationState?: GarageVentilationState;
}
