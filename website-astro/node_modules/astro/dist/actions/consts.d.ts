export declare const ACTIONS_TYPES_FILE = "actions.d.ts";
export declare const VIRTUAL_MODULE_ID = "astro:actions";
export declare const RESOLVED_VIRTUAL_MODULE_ID: string;
/** Used to expose shared utilities, with server or client specific implementations */
export declare const RUNTIME_VIRTUAL_MODULE_ID = "virtual:astro:actions/runtime";
export declare const RESOLVED_RUNTIME_VIRTUAL_MODULE_ID: string;
export declare const ENTRYPOINT_VIRTUAL_MODULE_ID = "virtual:astro:actions/entrypoint";
export declare const RESOLVED_ENTRYPOINT_VIRTUAL_MODULE_ID: string;
/** Used to pass data from the config to the main virtual module */
export declare const OPTIONS_VIRTUAL_MODULE_ID = "virtual:astro:actions/options";
export declare const RESOLVED_OPTIONS_VIRTUAL_MODULE_ID: string;
export declare const RESOLVED_NOOP_ENTRYPOINT_VIRTUAL_MODULE_ID = "\0virtual:astro:actions/noop-entrypoint";
export declare const ACTION_QUERY_PARAMS: {
    actionName: string;
    actionPayload: string;
};
export declare const ACTION_RPC_ROUTE_PATTERN = "/_actions/[...path]";
