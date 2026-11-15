namespace Acme.Services
{
    public interface IService { }

    public abstract class BaseService
    {
        protected void Log(string message) { }
    }
}
