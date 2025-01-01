import { List } from "@material/web/list/internal/list.js";
import { styles } from "@material/web/list/internal/list-styles.css.js";
export class EwList extends List {
}
EwList.styles = [styles];
customElements.define("ew-list", EwList);
