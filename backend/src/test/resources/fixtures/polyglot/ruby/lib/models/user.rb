module App
  module Models
    class User
      def initialize(name)
        @name = name
      end

      def display
        @name
      end
    end
  end
end
