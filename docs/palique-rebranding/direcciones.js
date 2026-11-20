const directions = [
  ["editorial", "A · ORIGINAL", "Conversación editorial", "Papel, ciruela y lima. Titulares serif y sesión activa en tinta.", "La más expresiva. El cambio a fondo oscuro hace visible la sesión activa, pero también es el salto visual más marcado."],
  ["cafe", "B · NUEVA", "Café de idiomas", "Arena, terracota y oliva. Tipografía redondeada y fondos cálidos.", "La más cercana al carácter actual. Da continuidad al orbe y suaviza la transición, aunque resulta menos rupturista."],
  ["signal", "C · NUEVA", "Señal clara", "Azul noche, blanco mineral y cobalto. Sans contundente y formas compactas.", "La más directa. Los controles ganan protagonismo; el acento melocotón aporta calidez sin competir con la acción principal."],
];

const template = document.querySelector("#direction");
const grid = document.querySelector("#directions");
for (const [id, label, title, description, tradeoff] of directions) {
  const fragment = template.content.cloneNode(true);
  const card = fragment.querySelector(".direction");
  card.dataset.theme = id;
  for (const [selector, value] of [[".tag", label], ["h2", title], [".description", description], [".tradeoff", tradeoff]]) {
    card.querySelector(selector).textContent = value;
  }
  grid.append(fragment);
  const styles = getComputedStyle(card);
  for (const role of ["fondo", "texto", "accion", "acento"]) {
    const value = styles.getPropertyValue("--" + role).trim();
    const swatch = document.createElement("span");
    swatch.style.setProperty("--sample", value);
    swatch.textContent = value.toUpperCase();
    swatch.setAttribute("aria-label", role + ": " + value);
    card.querySelector(".swatches").append(swatch);
  }
}
