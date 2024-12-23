import { Divider } from "@material/web/divider/internal/divider.js";
import { styles } from "@material/web/divider/internal/divider-styles.css.js";
export class EwDivider extends Divider {
}
EwDivider.styles = [styles];
customElements.define("ew-divider", EwDivider);
