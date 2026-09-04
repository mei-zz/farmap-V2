import { create } from "zustand";

export type Crop = {
    date: string;
    id: number;
    latitude: number;
    longitude: number;
    info: {
        diseases: string;
        rate: number;
        size: number;
        yield: number;
        phenology: number;
        growth: number;
        impact: number;
        disease: number;
        potassium: number;
        phosphorus: number;
        nitrogen: number;
    };
    url: string;
};
type Location = {
    id: number;
    latitude: number;
    longitude: number;
};

export type FarmStoreState = {
    address: string;
    center: string;
    zoom: number;
    // components: ("map" | "operations" | "weather" | "statistic")[];
    id: number;
    name: string;
    type: "citrus" | "plum" | "rice";
    crops: Crop[];
    locations: Location[];
    // User informations
    userId: number;
    username: string;
};
type FarmStoreActions = {
    setFarm: (farm: FarmStoreState) => void;
};

type FarmStore = FarmStoreState & FarmStoreActions;

const initFarmStore: FarmStoreState = {
    address: "",
    center: "",
    zoom: -1,
    id: -1,
    name: "",
    type: "citrus",
    crops: [],
    locations: [],
    userId: -1,
    username: "",
};
export const useFarmStore = create<FarmStore>((set) => ({
    ...initFarmStore,
    setFarm: (farm) => set(() => ({ ...farm })),
}));
