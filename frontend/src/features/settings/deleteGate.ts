/** The word that has to be typed before the delete button works. */
export const DELETE_WORD = 'DELETE';

/**
 * Whether the confirm dialog's button may be pressed: the word typed exactly (case sensitive, no spaces around it) and,
 * for an account with a password, a password entered. The server still checks the password; this only gates the click.
 */
export function canConfirmDeletion(word: string, password: string, hasPassword: boolean): boolean {
  return word === DELETE_WORD && (!hasPassword || password.length > 0);
}
