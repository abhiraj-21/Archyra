import { useMutation } from '@tanstack/react-query';
import { forgotPassword, register, resendVerification, resetPassword, verifyEmail } from '@/api/endpoints/auth';
import { runOnce } from '@/lib/once';

export const useRegister = () =>
  useMutation({ mutationFn: (v: { name: string; email: string; password: string }) => register(v.name, v.email, v.password) });
export const useResendVerification = () => useMutation({ mutationFn: (email: string) => resendVerification(email) });
export const useForgotPassword = () => useMutation({ mutationFn: (email: string) => forgotPassword(email) });
export const useResetPassword = () =>
  useMutation({ mutationFn: (v: { token: string; newPassword: string }) => resetPassword(v.token, v.newPassword) });

/** Verification tokens are single-use: the request is made at most once per token per page load. */
export function verifyEmailOnce(token: string) {
  return runOnce(`verify-email:${token}`, () => verifyEmail(token));
}
