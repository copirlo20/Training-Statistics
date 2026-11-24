const SVG_NS = 'http://www.w3.org/2000/svg';
const SPRITE = 'assets/icons.svg';

/** Biểu tượng từ bộ assets/icons.svg. Mặc định chỉ để trang trí (aria-hidden). */
export function icon(name, { small = false, label = null } = {}) {
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', small ? 'icon icon-sm' : 'icon');
    svg.setAttribute('viewBox', '0 0 24 24');
    if (label) {
        svg.setAttribute('role', 'img');
        svg.setAttribute('aria-label', label);
    } else {
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('focusable', 'false');
    }
    const use = document.createElementNS(SVG_NS, 'use');
    use.setAttribute('href', `${SPRITE}#i-${name}`);
    svg.appendChild(use);
    return svg;
}
