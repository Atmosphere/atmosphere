import type { Prompt } from '../definitions.js';
export declare class PromptsPrompt implements Prompt {
    #private;
    constructor({ force }: {
        force: boolean;
    });
    confirm({ message, defaultValue, }: {
        message: string;
        defaultValue?: boolean;
    }): Promise<boolean>;
}
