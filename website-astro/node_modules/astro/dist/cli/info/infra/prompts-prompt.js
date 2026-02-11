import prompts from "prompts";
class PromptsPrompt {
  #force;
  constructor({ force }) {
    this.#force = force;
  }
  async confirm({
    message,
    defaultValue
  }) {
    if (this.#force) {
      return true;
    }
    const { value } = await prompts({
      type: "confirm",
      name: "value",
      message,
      initial: defaultValue
    });
    return value;
  }
}
export {
  PromptsPrompt
};
