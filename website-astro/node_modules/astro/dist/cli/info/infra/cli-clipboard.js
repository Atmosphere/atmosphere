async function getExecInputForPlatform({
  platform,
  commandExecutor
}) {
  if (platform === "darwin") {
    return ["pbcopy"];
  }
  if (platform === "win32") {
    return ["clip"];
  }
  const unixCommands = [
    ["xclip", ["-selection", "clipboard"]],
    ["wl-copy", []]
  ];
  for (const [unixCommand, unixArgs] of unixCommands) {
    try {
      const { stdout } = await commandExecutor.execute("which", [unixCommand]);
      if (stdout.trim()) {
        return [unixCommand, unixArgs];
      }
    } catch {
      continue;
    }
  }
  return null;
}
class CliClipboard {
  #operatingSystemProvider;
  #commandExecutor;
  #logger;
  #prompt;
  constructor({
    operatingSystemProvider,
    commandExecutor,
    logger,
    prompt
  }) {
    this.#operatingSystemProvider = operatingSystemProvider;
    this.#commandExecutor = commandExecutor;
    this.#logger = logger;
    this.#prompt = prompt;
  }
  async copy(text) {
    text = text.trim();
    const platform = this.#operatingSystemProvider.name;
    const input = await getExecInputForPlatform({
      platform,
      commandExecutor: this.#commandExecutor
    });
    if (!input) {
      this.#logger.warn("SKIP_FORMAT", "Clipboard command not found!");
      this.#logger.info("SKIP_FORMAT", "Please manually copy the text above.");
      return;
    }
    if (!await this.#prompt.confirm({
      message: "Copy to clipboard?",
      defaultValue: true
    })) {
      return;
    }
    try {
      const [command, args] = input;
      await this.#commandExecutor.execute(command, args, {
        input: text,
        stdio: ["pipe", "ignore", "ignore"]
      });
      this.#logger.info("SKIP_FORMAT", "Copied to clipboard!");
    } catch {
      this.#logger.error(
        "SKIP_FORMAT",
        "Sorry, something went wrong! Please copy the text above manually."
      );
    }
  }
}
export {
  CliClipboard
};
