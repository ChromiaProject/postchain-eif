import { Flag, HexString } from "./types";

export const createAuthDesc = (pubkey: HexString, flags: Flag[] = [Flag.TRANSFER]) => {
    return [
        0,
        [flags, pubkey],
        null,
    ]
}