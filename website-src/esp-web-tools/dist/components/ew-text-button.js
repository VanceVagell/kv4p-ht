import { styles as sharedStyles } from "@material/web/button/internal/shared-styles.css.js";
import { TextButton } from "@material/web/button/internal/text-button.js";
import { styles as textStyles } from "@material/web/button/internal/text-styles.css.js";
export class EwTextButton extends TextButton {
}
EwTextButton.styles = [sharedStyles, textStyles];
customElements.define("ew-text-button", EwTextButton);
