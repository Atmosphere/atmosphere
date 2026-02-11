function deduplicateDirectiveValues(existingDirective, newDirective) {
  const [directiveName, ...existingValues] = existingDirective.split(/\s+/).filter(Boolean);
  const [newDirectiveName, ...newValues] = newDirective.split(/\s+/).filter(Boolean);
  if (directiveName !== newDirectiveName) {
    return void 0;
  }
  const finalDirectives = Array.from(/* @__PURE__ */ new Set([...existingValues, ...newValues]));
  return `${directiveName} ${finalDirectives.join(" ")}`;
}
function pushDirective(directives, newDirective) {
  let deduplicated = false;
  if (directives.length === 0) {
    return [newDirective];
  }
  const finalDirectives = [];
  for (const directive of directives) {
    if (deduplicated) {
      finalDirectives.push(directive);
      continue;
    }
    const result = deduplicateDirectiveValues(directive, newDirective);
    if (result) {
      finalDirectives.push(result);
      deduplicated = true;
    } else {
      finalDirectives.push(directive);
      finalDirectives.push(newDirective);
    }
  }
  return finalDirectives;
}
export {
  deduplicateDirectiveValues,
  pushDirective
};
