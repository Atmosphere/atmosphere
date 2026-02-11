import type { Logger } from '../../../core/logger/core.js';
import type { CommandExecutor, OperatingSystemProvider } from '../../definitions.js';
import type { Clipboard, Prompt } from '../definitions.js';
export declare class CliClipboard implements Clipboard {
    #private;
    constructor({ operatingSystemProvider, commandExecutor, logger, prompt, }: {
        operatingSystemProvider: OperatingSystemProvider;
        commandExecutor: CommandExecutor;
        logger: Logger;
        prompt: Prompt;
    });
    copy(text: string): Promise<void>;
}
